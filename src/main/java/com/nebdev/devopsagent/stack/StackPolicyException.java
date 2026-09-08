package com.nebdev.devopsagent.stack;

public class StackPolicyException extends RuntimeException {

    private final StackPolicy.Evaluation evaluation;

    public StackPolicyException(StackPolicy.Evaluation evaluation) {
        super("Cette sélection contredit la politique. Confirme si tu assumes les conséquences.");
        this.evaluation = evaluation;
    }

    public StackPolicy.Evaluation evaluation() {
        return evaluation;
    }
}
