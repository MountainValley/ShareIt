package com.valley.ShareIt.enums;

public enum MsgTypeEnum {
    TEXT_CHANGED("文本粘贴板信息有更新"),
    FILE_CHANGED("文件中转站信息有更新");

    private String desc;
    MsgTypeEnum(String desc) {
        this.desc = desc;
    }
}
