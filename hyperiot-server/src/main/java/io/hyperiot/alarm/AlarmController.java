package io.hyperiot.alarm;

import io.hyperiot.common.PageResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmService service;

    public AlarmController(AlarmService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<Alarm> list(@RequestParam(required = false) String status, @RequestParam(required = false) String level,
                                  @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return service.page(status, level, page, size);
    }

    @PostMapping("/{id}/ack")
    public ResponseEntity<Void> ack(@PathVariable long id) {
        service.ack(id);
        return ResponseEntity.noContent().build();
    }
}
