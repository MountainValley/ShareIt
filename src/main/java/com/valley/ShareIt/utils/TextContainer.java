package com.valley.ShareIt.utils;

/**
 * @author dale
 * @since 2025/9/4
 **/
public class TextContainer {
    private volatile static String text;

    public static String getText() {
        return text;
    }

    public static void setText(String text) {
        TextContainer.text = text;
    }
}
