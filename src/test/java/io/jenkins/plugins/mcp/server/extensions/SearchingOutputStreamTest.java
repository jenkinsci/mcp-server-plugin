package io.jenkins.plugins.mcp.server.extensions;

import static io.jenkins.plugins.mcp.server.extensions.BuildLogsExtension.SearchingOutputStream;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchingOutputStreamTest {

    @Test
    void testSimpleStringMatch() throws IOException {
        SearchingOutputStream sos = new SearchingOutputStream("test", false, false, 10, 1);
        writeLines(sos, "line 1", "test line", "line 3");

        List<BuildLogsExtension.SearchMatch> matches = sos.getMatches();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0))
                .extracting(
                        BuildLogsExtension.SearchMatch::getMatchedLine,
                        BuildLogsExtension.SearchMatch::getMatchedLineNumber)
                .containsExactly("test line", 2L);
        assertThat(matches.get(0).getContextLines()).hasSize(3);
    }

    @Test
    void testCaseInsensitiveMatch() throws IOException {
        SearchingOutputStream sos = new SearchingOutputStream("TEST", false, true, 10, 1);
        writeLines(sos, "line 1", "test line", "line 3");

        List<BuildLogsExtension.SearchMatch> matches = sos.getMatches();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getMatchedLine()).isEqualTo("test line");
    }

    @Test
    void testRegexMatch() throws IOException {
        SearchingOutputStream sos = new SearchingOutputStream("t.st", true, false, 10, 1);
        writeLines(sos, "line 1", "test line", "tast line", "line 4");

        List<BuildLogsExtension.SearchMatch> matches = sos.getMatches();
        assertThat(matches).hasSize(2);
        assertThat(matches)
                .extracting(BuildLogsExtension.SearchMatch::getMatchedLine)
                .containsExactly("test line", "tast line");
    }

    @Test
    void testMaxMatches() throws IOException {
        SearchingOutputStream sos = new SearchingOutputStream("test", false, false, 2, 1);
        writeLines(sos, "test 1", "test 2", "test 3", "test 4");

        List<BuildLogsExtension.SearchMatch> matches = sos.getMatches();
        assertThat(matches).hasSize(2);
        assertThat(sos.hasMoreMatches()).isTrue();
    }

    @Test
    void testContextLines() throws IOException {
        SearchingOutputStream sos = new SearchingOutputStream("middle", false, false, 10, 2);
        writeLines(sos, "line 1", "line 2", "middle line", "line 4", "line 5");

        List<BuildLogsExtension.SearchMatch> matches = sos.getMatches();
        assertThat(matches).hasSize(1);

        BuildLogsExtension.SearchMatch match = matches.get(0);
        assertThat(match.getContextLines())
                .hasSize(5)
                .containsExactly("line 1", "line 2", "middle line", "line 4", "line 5");
    }

    @Test
    void testSearchingOutputStreamWithLargeContext() throws IOException {
        SearchingOutputStream sos = new SearchingOutputStream("Match on line 10", false, false, 10, 5);

        // Write 20 lines of log
        for (int i = 1; i <= 20; i++) {
            if (i == 10) {
                writeLines(sos, "Match on line 10");
            } else {
                writeLines(sos, "Line " + i);
            }
        }

        List<BuildLogsExtension.SearchMatch> matches = sos.getMatches();
        assertThat(matches).hasSize(1);

        BuildLogsExtension.SearchMatch match = matches.get(0);
        assertThat(match.getMatchedLineNumber()).isEqualTo(10);
        assertThat(match.getMatchedLine()).isEqualTo("Match on line 10");

        assertThat(match.getContextLines())
                .hasSize(11) // 5 before + match + 5 after
                .containsExactly(
                        "Line 5",
                        "Line 6",
                        "Line 7",
                        "Line 8",
                        "Line 9",
                        "Match on line 10",
                        "Line 11",
                        "Line 12",
                        "Line 13",
                        "Line 14",
                        "Line 15");

        assertThat(match.getContextStartLine()).isEqualTo(5);
        assertThat(match.getContextEndLine()).isEqualTo(15);
    }

    @Test
    void testSearchingOutputStreamWithMatchAtStart() throws IOException {
        SearchingOutputStream sos = new SearchingOutputStream("Match on line 1", false, false, 10, 5);

        for (int i = 1; i <= 10; i++) {
            writeLines(sos, "Line " + i + (i == 1 ? " Match on line 1" : ""));
        }

        List<BuildLogsExtension.SearchMatch> matches = sos.getMatches();
        assertThat(matches).hasSize(1);

        BuildLogsExtension.SearchMatch match = matches.get(0);
        assertThat(match.getMatchedLineNumber()).isEqualTo(1);
        assertThat(match.getMatchedLine()).isEqualTo("Line 1 Match on line 1");

        assertThat(match.getContextLines())
                .hasSize(6) // match + 5 after
                .containsExactly("Line 1 Match on line 1", "Line 2", "Line 3", "Line 4", "Line 5", "Line 6");

        assertThat(match.getContextStartLine()).isEqualTo(1);
        assertThat(match.getContextEndLine()).isEqualTo(6);
    }

    @Test
    void testSearchingOutputStreamWithMatchAtEnd() throws IOException {
        SearchingOutputStream sos = new SearchingOutputStream("Match on line 10", false, false, 10, 5);

        for (int i = 1; i <= 10; i++) {
            writeLines(sos, "Line " + i + (i == 10 ? " Match on line 10" : ""));
        }

        List<BuildLogsExtension.SearchMatch> matches = sos.getMatches();
        assertThat(matches).hasSize(1);

        BuildLogsExtension.SearchMatch match = matches.get(0);
        assertThat(match.getMatchedLineNumber()).isEqualTo(10);
        assertThat(match.getMatchedLine()).isEqualTo("Line 10 Match on line 10");

        assertThat(match.getContextLines())
                .hasSize(6) // 5 before + match
                .containsExactly("Line 5", "Line 6", "Line 7", "Line 8", "Line 9", "Line 10 Match on line 10");

        assertThat(match.getContextStartLine()).isEqualTo(5);
        assertThat(match.getContextEndLine()).isEqualTo(10);
    }

    @Test
    void testSearchingOutputStreamWithZeroContext() throws IOException {
        SearchingOutputStream sos = new SearchingOutputStream("test", false, false, 10, 0);
        writeLines(sos, "line 1", "test line", "another test line", "line 4");

        List<BuildLogsExtension.SearchMatch> matches = sos.getMatches();
        assertThat(matches).hasSize(2);

        assertThat(matches)
                .extracting(
                        BuildLogsExtension.SearchMatch::getMatchedLine,
                        BuildLogsExtension.SearchMatch::getMatchedLineNumber,
                        match -> match.getContextLines().size())
                .containsExactly(tuple("test line", 2L, 1), tuple("another test line", 3L, 1));

        for (BuildLogsExtension.SearchMatch match : matches) {
            assertThat(match.getContextLines()).containsExactly(match.getMatchedLine());
            assertThat(match.getContextStartLine()).isEqualTo(match.getMatchedLineNumber());
            assertThat(match.getContextEndLine()).isEqualTo(match.getMatchedLineNumber());
        }
    }

    private void writeLines(SearchingOutputStream sos, String... lines) throws IOException {
        for (String line : lines) {
            sos.write(line.getBytes(StandardCharsets.UTF_8));
            sos.write('\n');
        }
        sos.close();
    }
}
