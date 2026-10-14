package io.hyperiot.product;

import io.hyperiot.common.PageResult;
import io.hyperiot.model.ThingModel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/products")
public class ProductController {

    public record CreateRequest(@NotBlank @Size(max = 128) String name, @Size(max = 512) String description,
                                String nodeType, String protocol) {
    }

    public record UpdateRequest(@NotBlank @Size(max = 128) String name, @Size(max = 512) String description) {
    }

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<Product> list(@RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return service.page(keyword, page, size);
    }

    @PostMapping
    public Product create(@Valid @RequestBody CreateRequest req) {
        return service.create(req.name(), req.description(), req.nodeType(), req.protocol());
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable long id, @Valid @RequestBody UpdateRequest req) {
        return service.update(id, req.name(), req.description());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/thing-model")
    public ThingModel thingModel(@PathVariable long id) {
        return service.thingModel(id);
    }

    @PutMapping("/{id}/thing-model")
    public ThingModel updateThingModel(@PathVariable long id, @RequestBody ThingModel model) {
        return service.updateThingModel(id, model);
    }
}
