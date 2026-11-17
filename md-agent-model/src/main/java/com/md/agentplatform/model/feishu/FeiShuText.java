package com.md.agentplatform.model.feishu;

import lombok.Data;

@Data
public class FeiShuText {
    String text;

    public FeiShuText(String text) {
        this.text = text;
    }
}
