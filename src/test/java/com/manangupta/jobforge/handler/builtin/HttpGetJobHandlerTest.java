package com.manangupta.jobforge.handler.builtin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpGetJobHandlerTest {

    @Test
    void stripsMarkupScriptsAndEntities() {
        String html = "<html><head><style>body{}</style><script>alert(1)</script></head>"
                + "<body><h1>Title</h1><p>Hello &amp; <b>world</b></p><!-- c --><div>Next   line</div></body></html>";

        String text = HttpGetJobHandler.toText(html);

        assertThat(text).isEqualTo("Title\nHello & world\nNext line");
        assertThat(text).doesNotContain("alert", "body{}");
    }
}
