package de.bwjobs.document;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Component;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.Map;

/** Renders Thymeleaf HTML templates (templates/*.html) to PDF with openhtmltopdf. */
@Component
public class PdfRenderer {
    private static final List<String> FONT_CANDIDATES = List.of(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf");

    private final SpringTemplateEngine engine;
    private final File font;

    public PdfRenderer() {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/");
        r.setSuffix(".html");
        r.setTemplateMode(TemplateMode.HTML);
        r.setCharacterEncoding("UTF-8");
        this.engine = new SpringTemplateEngine();
        this.engine.setTemplateResolver(r);
        this.font = FONT_CANDIDATES.stream().map(File::new).filter(File::exists).findFirst().orElse(null);
    }

    public String html(String template, Map<String, Object> vars) {
        Context ctx = new Context();
        ctx.setVariables(vars);
        return engine.process(template, ctx);
    }

    public byte[] pdf(String template, Map<String, Object> vars) {
        String html = html(template, vars);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            if (font != null) b.useFont(font, "Body");
            b.withW3cDocument(new W3CDom().fromJsoup(Jsoup.parse(html)), "/");
            b.toStream(out);
            b.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
    }
}
