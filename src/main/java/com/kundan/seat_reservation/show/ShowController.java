package com.kundan.seat_reservation.show;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    public ResponseEntity<ShowResponse> createShow(@Valid @RequestBody CreateShowRequest request) {
        ShowResponse response = showService.createShow(request);

        return ResponseEntity
                .created(URI.create("/shows/" + response.id()))
                .body(response);
    }
}
