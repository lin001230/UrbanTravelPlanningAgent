package cn.szu.urbantravelplanningagent.knowledge;

import cn.hutool.core.text.csv.CsvData;
import cn.hutool.core.text.csv.CsvReadConfig;
import cn.hutool.core.text.csv.CsvReader;
import cn.hutool.core.text.csv.CsvRow;
import cn.hutool.core.text.csv.CsvUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把去重后的四张表各写成一句中文。
 * 句子由模板拼出来，不调用聊天模型。改措辞就改这里的几个方法。
 */
public class KnowledgeSentenceBuilder {

    private static final Map<String, String> DAY_TYPE_LABEL = Map.of(
            "working days", "工作日",
            "days off", "休息日",
            "holidays", "节假日"
    );

    private final Path outputDir;

    public KnowledgeSentenceBuilder(Path outputDir) {
        this.outputDir = outputDir;
    }

    public List<KnowledgeText> build() throws IOException {
        List<KnowledgeText> documents = new ArrayList<>();
        documents.addAll(stationDocuments(readRows("stations.csv")));
        documents.addAll(exitDocuments(readRows("exits.csv")));
        documents.addAll(edgeDocuments(readRows("subway_shenzhen_e.csv")));
        documents.addAll(trainDocuments(readRows("szmc_first_last_trains.csv")));
        return documents;
    }

    private List<KnowledgeText> stationDocuments(List<CsvRow> rows) {
        List<KnowledgeText> documents = new ArrayList<>();
        for (CsvRow row : rows) {
            String name = cell(row, "站点名字");
            String intro = cell(row, "站点介绍");
            String lines = joinedLines(cell(row, "线路名称"));
            // 介绍里通常已经写了站名。前面再写一次线路，避免线路只留在没有被嵌入的列里。
            String text = intro.isEmpty()
                    ? "深圳地铁" + lines + "的" + name + "站。"
                    : "深圳地铁" + lines + "的" + name + "站。" + intro;
            documents.add(new KnowledgeText(
                    "station:" + cell(row, "ID"),
                    "stations",
                    text,
                    Map.of("name", name, "lines", cell(row, "线路名称"))
            ));
        }
        return documents;
    }

    private List<KnowledgeText> exitDocuments(List<CsvRow> rows) {
        List<KnowledgeText> documents = new ArrayList<>();
        for (CsvRow row : rows) {
            String stop = cell(row, "公交站名");
            String gate = cell(row, "站点出入口");
            String routes = cell(row, "站点出口信息/公交换乘信息");
            // 线路编码是数据源内部编号，不是「1号线」这种对外名称，句子里不写它。
            String text = "公交站「" + stop + "」靠近地铁" + gate + "，可换乘公交：" + routes + "。";
            documents.add(new KnowledgeText(
                    "exit:" + cell(row, "自定义唯一标识"),
                    "exits",
                    text,
                    Map.of("stop", stop, "gate", gate)
            ));
        }
        return documents;
    }

    private List<KnowledgeText> edgeDocuments(List<CsvRow> rows) {
        List<KnowledgeText> documents = new ArrayList<>();
        for (CsvRow row : rows) {
            String line = cell(row, "line_name");
            String station1 = cell(row, "station1");
            String station2 = cell(row, "station2");
            String text = "深圳地铁" + line + "中，" + station1 + "站与" + station2 + "站相邻。";
            documents.add(new KnowledgeText(
                    "edge:" + line + ":" + station1 + ":" + station2,
                    "edges",
                    text,
                    Map.of("line", line, "station1", station1, "station2", station2)
            ));
        }
        return documents;
    }

    private List<KnowledgeText> trainDocuments(List<CsvRow> rows) {
        List<KnowledgeText> documents = new ArrayList<>();
        for (CsvRow row : rows) {
            String line = lineLabel(cell(row, "line"));
            String dayRaw = cell(row, "day_type");
            String day = DAY_TYPE_LABEL.getOrDefault(dayRaw, dayRaw);
            String station = cell(row, "station");
            String direction = cell(row, "direction");
            String first = cell(row, "first_train");
            String last = cell(row, "last_train");
            // 官方表用 -- 表示这个方向当天没有车，例如终点站的反向。
            String text;
            if (("--".equals(first) || first.isEmpty()) && ("--".equals(last) || last.isEmpty())) {
                text = "深圳地铁" + line + day + "，" + station + "站开往" + direction + "方向当日没有列车。";
            } else {
                text = "深圳地铁" + line + day + "，" + station + "站开往" + direction + "方向的首班车是" + first + "，末班车是" + last + "。";
            }
            documents.add(new KnowledgeText(
                    "train:" + line + ":" + day + ":" + station + ":" + direction,
                    "trains",
                    text,
                    Map.of("line", line, "day", day, "station", station, "direction", direction)
            ));
        }
        return documents;
    }

    /**
     * 把 "1"、"Line 6"、"Line 6 Branch" 统一成 "1号线"、"6号线"、"6号线支线"。
     * 已经是「1号线」这种写法的原样返回。
     */
    private static String lineLabel(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return "";
        }
        boolean branch = text.toLowerCase(Locale.ROOT).endsWith("branch");
        String number = text.replace("Line", "")
                .replace("line", "")
                .replace("Branch", "")
                .replace("branch", "")
                .trim();
        if (!number.isEmpty() && number.chars().allMatch(Character::isDigit)) {
            String label = number + "号线";
            return branch ? label + "支线" : label;
        }
        if (text.endsWith("号线") || text.endsWith("支线")) {
            return text;
        }
        return text;
    }

    /** 站点表里换乘站的线路是 "1,2"，句子里写成「1号线、2号线」。 */
    private static String joinedLines(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        List<String> labels = new ArrayList<>();
        for (String part : raw.split(",")) {
            String label = lineLabel(part);
            if (!label.isEmpty()) {
                labels.add(label);
            }
        }
        return String.join("、", labels);
    }

    private List<CsvRow> readRows(String filename) throws IOException {
        Path file = outputDir.resolve(filename);
        if (!Files.exists(file)) {
            throw new IllegalStateException("找不到 " + file + "，请先运行 knowledge/build/dedupe.py");
        }
        CsvReadConfig config = new CsvReadConfig();
        config.setContainsHeader(true);
        // 站点介绍里有换行，必须按引号把多行当成同一个字段。
        config.setFieldSeparator(',');
        try (BufferedReader reader = openUtf8(file); CsvReader csvReader = CsvUtil.getReader(config)) {
            CsvData data = csvReader.read(reader);
            return data.getRows();
        }
    }

    /** 去重脚本写出的是带 BOM 的 UTF-8，第一个表头不能把 BOM 当成列名的一部分。 */
    private static BufferedReader openUtf8(Path file) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
        reader.mark(1);
        int first = reader.read();
        if (first != '\uFEFF') {
            reader.reset();
        }
        return reader;
    }

    private static String cell(CsvRow row, String name) {
        String value = row.getByName(name);
        return value == null ? "" : value.trim();
    }
}
