package com.valley.ShareIt.controller;

import com.valley.ShareIt.enums.MsgTypeEnum;
import com.valley.ShareIt.utils.SseClientsManager;
import com.valley.ShareIt.utils.TextContainer;
import com.valley.ShareIt.vo.SubmitTextRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 便签
 * @author dale
 * @since 2024/12/7
 **/
@Controller
@RequestMapping("/notepad")
@Slf4j
public class NotepadController {

    @PostMapping("text")
    public ResponseEntity<String> submitText(@RequestBody SubmitTextRequest request) {
        TextContainer.setText(request.getText());
        SseClientsManager.sendMsgToAllClients(MsgTypeEnum.TEXT_CHANGED.name(), request.getText(), request.getClientId());
        return ResponseEntity.ok("提交成功！");
    }

    @GetMapping("text")
    public ResponseEntity<String> getText() {
        return ResponseEntity.ok(TextContainer.getText());
    }


}
