package com.mentalbridge.care.assessment;

import java.io.Serializable;
import java.util.UUID;

record AssessmentAnswerId(UUID submissionId, UUID questionId) implements Serializable {
}
