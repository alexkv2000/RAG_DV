package kvo.itsnn.rag.eval;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Управление eval-набором и прогонами качества поиска. */
@RestController
@RequestMapping("/api/rag/admin/eval")
public class EvalController {

    private final EvalService eval;

    public EvalController(EvalService eval) {
        this.eval = eval;
    }

    @GetMapping("/questions")
    public List<EvalService.Question> questions() {
        return eval.questions();
    }

    public record QuestionRequest(String question, String classifier,
                                  String expectFiles, String expectMust, String note) {}

    @PostMapping("/questions")
    public Map<String, Object> addQuestion(@RequestBody QuestionRequest req) {
        if (req.question() == null || req.question().isBlank())
            throw new IllegalArgumentException("question пустой");
        if (req.expectFiles() == null || req.expectFiles().isBlank())
            throw new IllegalArgumentException("expect_files пустой — укажите хотя бы один файл через ';'");
        String cls = req.classifier() == null || req.classifier().isBlank() ? null : req.classifier().strip();
        return Map.of("questionId", eval.addQuestion(req.question().strip(), cls,
                req.expectFiles().strip(), req.expectMust(), req.note()));
    }

    @DeleteMapping("/questions/{id}")
    public Map<String, String> deleteQuestion(@PathVariable int id) {
        eval.deleteQuestion(id);
        return Map.of("result", "deleted");
    }

    public record RunRequest(String label) {}

    /** Прогон всех включённых вопросов через полный путь поиска (без LLM). */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody(required = false) RunRequest req) {
        return eval.run(req == null ? null : req.label());
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        return eval.runs(20);
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> runDetails(@PathVariable int id) {
        return eval.runDetails(id);
    }
}