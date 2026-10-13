package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.TravelDocumentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/trips/{tripId}/documents")
public class TravelDocumentController {

  private final TravelDocumentService documentService;

  public TravelDocumentController(TravelDocumentService documentService) {
    this.documentService = documentService;
  }

  @GetMapping
  public Map<String, Object> findAll(@PathVariable String tripId, @CurrentUser UserContext user) {
    return documentService.findAll(tripId, user.id());
  }

  @GetMapping("/{documentId}")
  public Map<String, Object> findOne(@PathVariable String tripId, @PathVariable String documentId,
      @CurrentUser UserContext user) {
    return documentService.findOne(tripId, documentId, user.id());
  }

  @PostMapping
  public Map<String, Object> create(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return documentService.create(tripId, user.id(), dto);
  }

  @PatchMapping("/{documentId}")
  public Map<String, Object> update(@PathVariable String tripId, @PathVariable String documentId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return documentService.update(tripId, documentId, dto, user.id());
  }

  @DeleteMapping("/{documentId}")
  public Map<String, Object> delete(@PathVariable String tripId, @PathVariable String documentId,
      @CurrentUser UserContext user) {
    return documentService.delete(tripId, documentId, user.id());
  }
}
