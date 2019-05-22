package io.choerodon.workflow.domain.handler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

import io.choerodon.core.exception.CommonException;
import io.choerodon.workflow.api.controller.dto.DevopsPipelineDTO;
import io.choerodon.workflow.api.controller.dto.DevopsPipelineStageDTO;
import io.choerodon.workflow.api.controller.dto.DevopsPipelineTaskDTO;
import io.choerodon.workflow.infra.util.DynamicWorkflowUtil;
import org.activiti.bpmn.BpmnAutoLayout;
import org.activiti.bpmn.model.Process;
import org.activiti.bpmn.model.*;

/**
 * Created by Sheep on 2019/4/2.
 */
public class DevopsPipelineBpmnHandler {

    public static final String START_PROCESS = "startProcess";
    public static final String END_PROCESS = "endProcess";
    public static final String ADHOC_SUB_PROCESS = "subProcess";
    public static final String USER_TASK = "manual";
    public static final String SERVICE_TASK = "auto";
    public static final String DELEGATE_EXPRESSION = "delegateExpression";
    public static final String MANUAL = "manual";
    public static final String PROCESS = "Process";
    public static final String PARALLEL_GATE_WAY = "ParallelGateWay";
    public static final String SUB_START_PROCESS = "subStartProcess";
    public static final String END_START_PROCESS = "endStartProcess";

    public static BpmnModel initDevopsCDPipelineBpmn(DevopsPipelineDTO devopsPipelineDTO, Map<String, Object> params) {


        // 实例化BpmnModel对象
        BpmnModel model = new BpmnModel();

        DynamicWorkflowUtil dynamicWorkflowUtil = new DynamicWorkflowUtil();
        //生成主流程节点
        StartEvent startProcess = dynamicWorkflowUtil.createStartEvent(START_PROCESS);
        EndEvent endProcess = dynamicWorkflowUtil.createEndEvent(END_PROCESS);
        SequenceFlow startProcessToFirstStage = dynamicWorkflowUtil.createSequenceFlow(startProcess.getId(), ADHOC_SUB_PROCESS + 0);
        SequenceFlow lastStageToEndProcess = dynamicWorkflowUtil.createSequenceFlow(ADHOC_SUB_PROCESS + (devopsPipelineDTO.getStages().size() - 1), endProcess.getId());
        Process process = new Process();
        process.setId(PROCESS);
        process.setName(PROCESS);
        process.addFlowElement(startProcess);
        process.addFlowElement(endProcess);

        //生成每个stage的子流程
        for (int i = 0; i < devopsPipelineDTO.getStages().size(); i++) {

            DevopsPipelineStageDTO devopsPipelineStageDTO = devopsPipelineDTO.getStages().get(i);
            SubProcess subProcess = new SubProcess();
            subProcess.setId(ADHOC_SUB_PROCESS + i);
            subProcess.setName(ADHOC_SUB_PROCESS + i);
            StartEvent subProcessStart = dynamicWorkflowUtil.createStartEvent(SUB_START_PROCESS + i);
            EndEvent subProcessEnd = dynamicWorkflowUtil.createEndEvent(END_START_PROCESS + i);
            if (devopsPipelineStageDTO.getTasks().size() > 0) {
                subProcess.addFlowElement(subProcessStart);
                //每个子流程里面的任务如果是并行，只会有serviceTask
                ParallelGateway parallelGateway = null;
                if (devopsPipelineStageDTO.getParallel()) {
                    parallelGateway = dynamicWorkflowUtil.createParallelGateway(PARALLEL_GATE_WAY + i, PARALLEL_GATE_WAY + i);
                    SequenceFlow sequenceFlow = dynamicWorkflowUtil.createSequenceFlow(subProcessStart.getId(), parallelGateway.getId());
                    subProcess.addFlowElement(sequenceFlow);
                    subProcess.addFlowElement(parallelGateway);
                }

                //生成每个子流程内部的task
                for (int j = 0; j < devopsPipelineDTO.getStages().get(i).getTasks().size(); j++) {
                    DevopsPipelineTaskDTO devopsPipelineTaskDTO = devopsPipelineDTO.getStages().get(i).getTasks().get(j);
                    if (devopsPipelineTaskDTO.getTaskType().equals(USER_TASK)) {
                        //设置会签
                        UserTask userTask = null;
                        if (devopsPipelineTaskDTO.isMultiAssign()) {
                            userTask = dynamicWorkflowUtil.createUserTask(subProcess.getId() + "-" + USER_TASK + j, USER_TASK + devopsPipelineStageDTO.getStageRecordId() + devopsPipelineTaskDTO.getTaskId(), "${user}");
                            userTask.setLoopCharacteristics(getMultiInstanceLoopCharacteristics(devopsPipelineTaskDTO.isSign(), userTask.getName()));
                        } else {
                            userTask = dynamicWorkflowUtil.createUserTask(subProcess.getId() + "-" + USER_TASK + j, USER_TASK + "." + devopsPipelineStageDTO.getStageRecordId() + "." + devopsPipelineTaskDTO.getTaskId(), devopsPipelineTaskDTO.getUsernames().get(0));
                        }
                        //有用户审批任务只能是串行，此时把节点和上个任务节点连线
                        SequenceFlow sequenceFlow = dynamicWorkflowUtil.createSequenceFlow(getLastFlowElement(subProcess).getId(), userTask.getId());
                        subProcess.addFlowElement(sequenceFlow);
                        subProcess.addFlowElement(userTask);
                        params.put(userTask.getName(), devopsPipelineTaskDTO.getUsernames());
                        devopsPipelineTaskDTO.setTaskName(userTask.getName());
                    } else {
                        ServiceTask serviceTask = dynamicWorkflowUtil.createServiceTask(subProcess.getId() + "-" + SERVICE_TASK + SERVICE_TASK + "." + devopsPipelineDTO.getPipelineRecordId() + "." + devopsPipelineStageDTO.getStageRecordId() + "." + devopsPipelineTaskDTO.getTaskId(), SERVICE_TASK + "." + devopsPipelineDTO.getPipelineRecordId() + "." + devopsPipelineStageDTO.getStageRecordId() + "." + devopsPipelineTaskDTO.getTaskId());
                        serviceTask.setImplementation("${devopsDeployDelegate}");
                        serviceTask.setImplementationType(DELEGATE_EXPRESSION);
                        //如果是并行，只需要把所有的serviceTask和并行控制路由连线，如果不是并行，只需要和上个节点连线即可
                        if (parallelGateway != null) {
                            SequenceFlow sequenceFlow = dynamicWorkflowUtil.createSequenceFlow(parallelGateway.getId(), serviceTask.getId());
                            SequenceFlow sequenceFlow1 = dynamicWorkflowUtil.createSequenceFlow(serviceTask.getId(), subProcessEnd.getId());
                            subProcess.addFlowElement(serviceTask);
                            subProcess.addFlowElement(sequenceFlow);
                            subProcess.addFlowElement(sequenceFlow1);
                        } else {
                            SequenceFlow sequenceFlow = dynamicWorkflowUtil.createSequenceFlow(getLastFlowElement(subProcess).getId(), serviceTask.getId());
                            subProcess.addFlowElement(sequenceFlow);
                            subProcess.addFlowElement(serviceTask);
                        }
                        devopsPipelineTaskDTO.setTaskName(serviceTask.getName());
                    }
                }
                if (parallelGateway != null) {
                    subProcess.addFlowElement(subProcessEnd);
                } else {
                    SequenceFlow sequenceFlow = dynamicWorkflowUtil.createSequenceFlow(getLastFlowElement(subProcess).getId(), subProcessEnd.getId());
                    subProcess.addFlowElement(sequenceFlow);
                    subProcess.addFlowElement(subProcessEnd);
                }
            } else {
                subProcess.addFlowElement(subProcessStart);
                subProcess.addFlowElement(subProcessEnd);
                SequenceFlow sequenceFlow = dynamicWorkflowUtil.createSequenceFlow(subProcessStart.getId(), subProcessEnd.getId());
                subProcess.addFlowElement(sequenceFlow);
            }
            //设置每个子流程之间是否需要人工审核
            if (devopsPipelineStageDTO.getNextStageTriggerType() != null) {
                if (devopsPipelineStageDTO.getNextStageTriggerType().equals(MANUAL)) {
                    UserTask userTask = null;
                    if (devopsPipelineStageDTO.isMultiAssign()) {
                        userTask = dynamicWorkflowUtil.createUserTask(subProcess.getId() + "ToNext", subProcess.getId() + "ToNext" + devopsPipelineStageDTO.getStageRecordId(), "${user}");
                        ActivitiListener activitiListener = new ActivitiListener();
                        activitiListener.setEvent("create");
                        activitiListener.setImplementation("${mangerTaskCreateDelegate}");
                        activitiListener.setImplementationType(DELEGATE_EXPRESSION);
                        userTask.setLoopCharacteristics(getMultiInstanceLoopCharacteristics(false, userTask.getName()));
                    } else {
                        userTask = dynamicWorkflowUtil.createUserTask(subProcess.getId() + "ToNext", subProcess.getId() + "ToNext" + "." + devopsPipelineStageDTO.getStageRecordId(), devopsPipelineStageDTO.getUsernames().get(0));
                    }
                    SequenceFlow stageToUserTask = dynamicWorkflowUtil.createSequenceFlow(subProcess.getId(), userTask.getId());
                    SequenceFlow userTaskToNextStage = dynamicWorkflowUtil.createSequenceFlow(userTask.getId(), ADHOC_SUB_PROCESS + (i + 1));
                    process.addFlowElement(userTask);
                    process.addFlowElement(stageToUserTask);
                    process.addFlowElement(userTaskToNextStage);
                    params.put(userTask.getName(), devopsPipelineStageDTO.getUsernames());
                    devopsPipelineStageDTO.setStageTaskName(userTask.getName());
                } else {
                    SequenceFlow stageToNextStage = dynamicWorkflowUtil.createSequenceFlow(subProcess.getId(), ADHOC_SUB_PROCESS + (i + 1));
                    process.addFlowElement(stageToNextStage);
                }
            }
            process.addFlowElement(subProcess);
            devopsPipelineStageDTO.setStageName(subProcess.getName());
        }
        process.addFlowElement(startProcessToFirstStage);
        process.addFlowElement(lastStageToEndProcess);
        model.addProcess(process);

        //自动布局
        new BpmnAutoLayout(model).execute();
        return model;
    }

    private static MultiInstanceLoopCharacteristics getMultiInstanceLoopCharacteristics(Boolean isSign, String userList) {
        MultiInstanceLoopCharacteristics multiInstanceLoopCharacteristics = new MultiInstanceLoopCharacteristics();
        multiInstanceLoopCharacteristics.setElementVariable("user");
        multiInstanceLoopCharacteristics.setCompletionCondition("${nrOfCompletedInstances==1}");
        if (isSign) {
            multiInstanceLoopCharacteristics.setCompletionCondition("${nrOfCompletedInstances/nrOfInstances == 1}");
        }
        multiInstanceLoopCharacteristics.setInputDataItem("${" + userList + "}");
        return multiInstanceLoopCharacteristics;
    }


    public static void saveDataToFile(String path, String fileName, String data) {
        File file = new File(path + System.getProperty("file.separator") + fileName);
        //如果文件不存在，则新建一个
        if (!file.exists()) {
            new File(path).mkdirs();
            try {
                if (!file.createNewFile()) {
                    throw new CommonException("error.file.create");
                }
            } catch (IOException e) {
            }
        }
        //写入
        try (FileOutputStream fileOutputStream = new FileOutputStream(file, false)) {
            try (OutputStreamWriter outputStreamWriter = new OutputStreamWriter(fileOutputStream, StandardCharsets.UTF_8)) {
                try (BufferedWriter writer = new BufferedWriter(outputStreamWriter)) {
                    writer.write(data);
                }
            }
        } catch (IOException e) {
        }
    }


    public static FlowElement getLastFlowElement(SubProcess subProcess) {
        Iterator it = subProcess.getFlowElements().iterator();
        int a = 0;
        while (it.hasNext()) {
            FlowElement flowElement = (FlowElement) it.next();
            if (a == subProcess.getFlowElements().size() - 1) {
                return flowElement;
            }
            a++;
        }
        return null;
    }
}
