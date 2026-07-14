package org.example.ai.agent.capability.evaluation.service;

import org.example.ai.agent.capability.evaluation.dto.CapabilityRouteFeedbackDTO;
import org.example.ai.agent.capability.evaluation.entity.CapabilityRouteFeedback;

public interface CapabilityRouteFeedbackService {

    CapabilityRouteFeedback submit(CapabilityRouteFeedbackDTO dto, String submittedBy );

    void approve( Long feedbackId,String reviewedBy );

    void reject( Long feedbackId, String reviewedBy );
}