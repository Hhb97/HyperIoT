package io.hyperiot.command;

import io.hyperiot.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final CommandService service;

    public CommandController(CommandService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<Command> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.page(null, status, page, size);
    }
}
