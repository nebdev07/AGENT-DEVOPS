package com.nebdev.devopsagent.stack;

/**
 * Messages opérateur : cause explicite + où ça se passe + quoi faire.
 * Évite les libellés ambigus affichés loin du champ concerné.
 */
public final class OperatorMessage {

    private OperatorMessage() {
    }

    public static String of(String cause, String where, String action) {
        return "Cause : " + cause + "\nOù : " + where + "\nÀ faire : " + action;
    }
}
