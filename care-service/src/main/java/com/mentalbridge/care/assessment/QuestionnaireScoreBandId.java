package com.mentalbridge.care.assessment;

import java.io.Serializable;
import java.util.UUID;

record QuestionnaireScoreBandId(UUID definitionId, ScreeningLevel code) implements Serializable {
}
