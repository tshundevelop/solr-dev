import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class HybridSearchController {
    @GetMapping("/")
    public String index() {
        return "search";
    }

    @PostMapping("/search")
    public String search(@RequestParam String query, Model model) {
        // HybridSearchの検索ロジックを呼び出す
        // List<Result> results = HybridSearch.search(query);
        // model.addAttribute("results", results);
        return "search";
    }
}
