package com.chatagent.knowledge;

import com.chatagent.common.ApiException;
import com.chatagent.security.JwtPrincipal;
import com.chatagent.security.SecurityUtils;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/knowledge")
@RequiredArgsConstructor
public class KnowledgeAdminController {

    private final KnowledgeAdminService adminService;
    private final RagEvalService ragEvalService;

    public record DocResponse(
            long id,
            String docTitle,
            String sourcePath,
            Integer version,
            String docHash,
            String createdAt,
            String updatedAt,
            int chunkCount) {}

    private static void requireAdmin() {
        JwtPrincipal p = SecurityUtils.requirePrincipal();
        if (p.role() == null || !p.role().equalsIgnoreCase("ADMIN")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Admin only");
        }
    }

    @GetMapping("/docs")
    public List<DocResponse> listDocs() {
        requireAdmin();
        return adminService.listDocs().stream()
                .map(
                        d ->
                                new DocResponse(
                                        d.id(),
                                        d.docTitle(),
                                        d.sourcePath(),
                                        d.version(),
                                        d.docHash(),
                                        d.createdAt() != null ? d.createdAt().toString() : null,
                                        d.updatedAt() != null ? d.updatedAt().toString() : null,
                                        d.chunkCount()))
                .toList();
    }

    @PostMapping(value = "/docs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocResponse> upload(
            @RequestParam("file") MultipartFile file, @RequestParam(value = "title", required = false) String title)
            throws Exception {
        requireAdmin();
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Empty upload");
        }
        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String sourcePath = "upload:" + System.currentTimeMillis() + ":" + filename;
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        long id = adminService.createDoc(title != null ? title : filename, sourcePath, text);
        KnowledgeAdminService.KnowledgeDocRow row =
                adminService.listDocs().stream().filter(d -> d.id() == id).findFirst().orElse(null);
        if (row == null) {
            return ResponseEntity.status(201).body(new DocResponse(id, title, sourcePath, 1, null, null, null, 0));
        }
        return ResponseEntity.status(201)
                .body(
                        new DocResponse(
                                row.id(),
                                row.docTitle(),
                                row.sourcePath(),
                                row.version(),
                                row.docHash(),
                                row.createdAt() != null ? row.createdAt().toString() : null,
                                row.updatedAt() != null ? row.updatedAt().toString() : null,
                                row.chunkCount()));
    }

    @PutMapping("/docs/{docId}")
    public ResponseEntity<Void> update(
            @PathVariable long docId,
            @RequestParam("title") String title,
            @RequestParam("text") String text) {
        requireAdmin();
        adminService.updateDoc(docId, title, text);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/docs/{docId}/reindex")
    public ResponseEntity<Void> reindex(@PathVariable long docId) {
        requireAdmin();
        adminService.reindex(docId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/docs/{docId}/rollback")
    public ResponseEntity<Void> rollback(@PathVariable long docId, @RequestParam("toVersion") int toVersion) {
        requireAdmin();
        adminService.rollback(docId, toVersion);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/eval/run")
    public RagEvalService.EvalRunResult runEval(
            @RequestParam(value = "k", required = false, defaultValue = "5") int k,
            @RequestParam(value = "minScore", required = false, defaultValue = "0.0") double minScore) {
        requireAdmin();
        return ragEvalService.run(new RagEvalService.EvalRunRequest(k, minScore));
    }

    @GetMapping("/eval/runs")
    public List<RagEvalService.EvalRunResult> listEvalRuns(
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        requireAdmin();
        return ragEvalService.listRuns(limit);
    }
}

