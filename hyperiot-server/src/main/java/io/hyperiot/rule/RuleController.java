package io.hyperiot.rule;

import io.hyperiot.common.PageResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    private final RuleService service;

    public RuleController(RuleService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<Rule> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return service.page(page, size);
    }

    @PostMapping
    public Rule create(@RequestBody Rule rule) {
        return service.create(rule);
    }

    @GetMapping("/{id}")
    public Rule get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public Rule update(@PathVariable long id, @RequestBody Rule rule) {
        return service.update(id, rule);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable long id) {
        service.setEnabled(id, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable long id) {
        service.setEnabled(id, false);
        return ResponseEntity.noContent().build();
    }
}
