package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.PhotoService;
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
@RequestMapping("/api/trips/{tripId}/photos")
public class PhotoController {

  private final PhotoService photoService;

  public PhotoController(PhotoService photoService) {
    this.photoService = photoService;
  }

  @GetMapping
  public Map<String, Object> findAll(@PathVariable String tripId, @CurrentUser UserContext user) {
    return photoService.findAll(tripId, user.id());
  }

  @GetMapping("/{photoId}")
  public Map<String, Object> findOne(@PathVariable String tripId, @PathVariable String photoId,
      @CurrentUser UserContext user) {
    return photoService.findOne(tripId, photoId, user.id());
  }

  @PostMapping
  public Map<String, Object> create(@PathVariable String tripId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return photoService.create(tripId, user.id(), dto);
  }

  @PatchMapping("/{photoId}")
  public Map<String, Object> update(@PathVariable String tripId, @PathVariable String photoId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return photoService.update(tripId, photoId, dto, user.id());
  }

  @DeleteMapping("/{photoId}")
  public Map<String, Object> delete(@PathVariable String tripId, @PathVariable String photoId,
      @CurrentUser UserContext user) {
    return photoService.delete(tripId, photoId, user.id());
  }
}
