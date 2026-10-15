package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.TripPdfService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/trips/{tripId}/export")
public class TripPdfController {

  private final TripPdfService tripPdfService;

  public TripPdfController(TripPdfService tripPdfService) {
    this.tripPdfService = tripPdfService;
  }

  @GetMapping("/pdf")
  public ResponseEntity<byte[]> exportPdf(@PathVariable String tripId,
      @CurrentUser UserContext user) throws java.io.IOException {
    Map<String, Object> result = tripPdfService.export(tripId, user.id());
    byte[] buffer = (byte[]) result.get("buffer");
    String filename = (String) result.get("filename");
    String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename*=UTF-8''" + encoded)
        .contentType(MediaType.APPLICATION_PDF)
        .body(buffer);
  }
}
