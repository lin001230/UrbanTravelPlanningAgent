package cn.szu.urbantravelplanningagent.tool;

import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用腾讯公交路线接口，把途经站名保留下来交给模型。
 * MCP 的 directionTransit 只返回「经过几站」，站名要靠这里的 stations 字段。
 */
public class TransitRouteTool {

    /** 与 @Tool 方法名一致。 */
    public static final String NAME = "planTransit";

    private static final String TRANSIT_API_URL = "https://apis.map.qq.com/ws/direction/v1/transit/";

    private static final int TIMEOUT_MILLIS = 15000;

    /** 一次最多交给模型的方案数，避免把全部备选都塞进上下文。 */
    private static final int MAX_ROUTES = 3;

    /**
     * 查询起终点之间的途经站。失败时返回给模型可读的说明，不向外抛出。
     */
    @Tool(description = "根据起点和终点坐标查询公共交通途经站。返回每条方案的线路、上下车站，以及按乘坐顺序排列的途经站名。站名只从这里抄，不要自行补充。")
    public String planTransit(
            @ToolParam(description = "起点坐标，纬度在前、经度在后，例如 22.445,114.035") String from,
            @ToolParam(description = "终点坐标，纬度在前、经度在后，例如 22.337,114.140") String to) {
        // 检验起点坐标格式
        String fromError = validateCoordinate(from, "起点");
        if (fromError != null) {
            return fromError;
        }
        // 检验终点坐标格式
        String toError = validateCoordinate(to, "终点");
        if (toError != null) {
            return toError;
        }
        // 获取腾讯地图 API 密钥
        String apiKey = SpringUtil.getProperty("tencent-map.api-key");
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            // 如果 API 密钥未配置，则返回错误信息
            return "planTransit error：tencent-map.api-key 未配置，请填写 TENCENT_MAP_API_KEY";
        }

        try (HttpResponse response = HttpRequest.get(TRANSIT_API_URL)
                .form("from", from.trim())
                .form("to", to.trim())
                .form("key", apiKey)
                .form("output", "json")
                .timeout(TIMEOUT_MILLIS)
                .execute()) {
            // 获取响应体
            String responseBody = response.body();
            if (response.getStatus() != 200) {
                // 如果响应状态码不是 200，则返回错误信息
                return "planTransit error：HTTP " + response.getStatus();
            }
            if (responseBody == null || responseBody.isBlank() || !JSONUtil.isTypeJSONObject(responseBody)) {
                // 如果响应体不是合法 JSON，则返回错误信息
                return "planTransit error：路线响应不是合法 JSON";
            }
            // 格式化路线
            return formatRoutes(JSONUtil.parseObj(responseBody));
        } catch (Exception e) {
            String message = e.getMessage();
            return "planTransit error：" + (message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
        }
    }

    /** 坐标必须是「纬度,经度」。返回 null 表示通过。 */
    private String validateCoordinate(String value, String label) {
        if (value == null || value.isBlank()) {
            return "planTransit error：" + label + "坐标不能为空";
        }
        String[] parts = value.trim().split(",");
        if (parts.length != 2) {
            return "planTransit error：" + label + "坐标格式应为纬度,经度";
        }
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lng = Double.parseDouble(parts[1].trim());
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
                return "planTransit error：" + label + "坐标超出范围";
            }
        } catch (NumberFormatException e) {
            return "planTransit error：" + label + "坐标不是数字";
        }
        return null;
    }

    /** 格式化路线 */
    private String formatRoutes(JSONObject json) {
        // 获取状态码
        int status = json.getInt("status", -1);
        if (status != 0) {
            // 如果状态码不是 0，则返回错误信息
            String message = json.getStr("message");
            return "planTransit error：" + status + (message == null || message.isBlank() ? "" : " " + message);
        }
        // 获取路线
        JSONArray routes = json.getByPath("result.routes", JSONArray.class);
        if (routes == null || routes.isEmpty()) {
            // 如果路线为空，则返回错误信息
            return "没有查到公共交通方案";
        }
        // 获取路线数量
        int count = Math.min(routes.size(), MAX_ROUTES);
        // 构建返回文本
        StringBuilder text = new StringBuilder();
        // 添加方案数量
        text.append("公共交通方案共 ").append(count).append(" 条。途经站只准照抄下列名称，没有出现的站不要补充。");
        // 添加剩余方案数量
        if (routes.size() > MAX_ROUTES) {
            text.append("其余 ").append(routes.size() - MAX_ROUTES).append(" 条未列出。");
        }
        for (int i = 0; i < count; i++) {
            // 格式化路线
            text.append("\n\n方案").append(i + 1).append("：").append(formatRoute((JSONObject) routes.get(i)));
        }
        return text.toString();
    }

    /** 格式化单条路线 */
    private String formatRoute(JSONObject route) {
        // 获取步骤
        JSONArray steps = route.getJSONArray("steps");
        // 步行距离
        int walkingMeters = 0;
        if (steps != null) {
            // 遍历步骤
            for (Object item : steps) {
                JSONObject step = (JSONObject) item;
                // 如果是步行，则累加步行距离
                if ("WALKING".equalsIgnoreCase(step.getStr("mode"))) {
                    walkingMeters += step.getInt("distance", 0);
                }
            }
        }
        // 构建返回文本
        StringBuilder text = new StringBuilder();
        // 添加总时间
        text.append("约 ").append(route.getInt("duration", 0)).append(" 分钟，总距离 ")
                .append(route.getInt("distance", 0)).append(" 米，步行 ").append(walkingMeters).append(" 米");
        // 如果步骤为空，则返回文本
        if (steps == null) {
            return text.toString();
        }
        // 遍历步骤
        for (Object item : steps) {
            JSONObject step = (JSONObject) item;
            String mode = step.getStr("mode");
            // 如果是步行，则添加步行距离和时间
            if ("WALKING".equalsIgnoreCase(mode)) {
                text.append("\n- 步行 ").append(step.getInt("distance", 0)).append(" 米，约 ")
                        .append(step.getInt("duration", 0)).append(" 分钟");
            } else if ("TRANSIT".equalsIgnoreCase(mode)) {
                // 如果是公交或地铁，则添加公交或地铁信息
                text.append("\n- ").append(formatTransit(step));
            }
        }
        return text.toString();
    }

    /** 同一段可能有多条并行线路，完整站名在第一条。 */
    private String formatTransit(JSONObject step) {
        // 获取线路
        JSONArray lines = step.getJSONArray("lines");
        if (lines == null || lines.isEmpty()) {
            return "公共交通段没有返回线路";
        }
        // 获取第一条线路
        JSONObject line = (JSONObject) lines.get(0);
        // 获取交通工具
        String vehicle = line.getStr("vehicle");
        // 构建返回文本
        StringBuilder text = new StringBuilder();
        // 添加线路名称
        text.append("乘坐").append(blankToUnknown(line.getStr("title")));
        // 获取目的地
        String direction = line.getByPath("destination.title", String.class);
        // 如果目的地不为空，则添加目的地
        if (direction != null && !direction.isBlank()) {
            text.append("，往").append(direction);
        }
        // 添加上车地点
        text.append("。从").append(placeTitle(line.getJSONObject("geton"))).append("上车");
        // 获取途经站名
        String stops = stationNames(line.getJSONArray("stations"));
        if (stops.isEmpty()) {
            // 如果途经站名为空，则添加错误信息
            text.append("，途经站名未返回，不要编造。经停站数 ").append(line.getInt("station_count", 0));
        } else {
            // 如果途经站名不为空，则添加途经站名
            text.append("，途经").append(stops);
        }
        // 添加下车地点
        text.append("，在").append(placeTitle(line.getJSONObject("getoff"))).append("下车");
        // 添加总时间
        text.append("。约 ").append(line.getInt("duration", 0)).append(" 分钟，费用 ")
                .append(formatPrice(vehicle, line.getInt("price", -1)));
        // 获取运行状态
        String running = runningStatus(line.getInt("running_status", 300));
        if (!running.isEmpty()) {
            text.append("，").append(running);
        }
        // 如果有多条线路，则添加备选线路
        if (lines.size() > 1) {
            // 获取备选线路
            List<String> alternatives = new ArrayList<>();
            // 遍历备选线路
            for (int i = 1; i < lines.size(); i++) {
                String title = ((JSONObject) lines.get(i)).getStr("title");
                if (title != null && !title.isBlank()) {
                    alternatives.add(title);
                }
            }
            // 如果备选线路不为空，则添加备选线路
            if (!alternatives.isEmpty()) {
                text.append("。同段还可乘坐").append(String.join("、", alternatives));
            }
        }
        return text.toString();
    }

    /** 获取途经站名 */
    private String stationNames(JSONArray stations) {
        // 如果途经站名为空，则返回空字符串
        if (stations == null || stations.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        // 遍历途经站
        for (Object item : stations) {
            // 获取站名
            String title = ((JSONObject) item).getStr("title");
            // 如果站名不为空，则添加站名
            if (title != null && !title.isBlank()) {
                names.add(title);
            }
        }
        return String.join("、", names);
    }

    /** 地铁上下车站有时带出入口。 */
    private String placeTitle(JSONObject place) {
        if (place == null) {
            return "未知站点";
        }
        // 获取站点名称
        String title = place.getStr("title");
        if (title == null || title.isBlank()) {
            title = "未知站点";
        }
        // 获取出口名称
        String exit = place.getByPath("exit.title", String.class);
        // 如果出口名称为空，则返回站点名称
        if (exit == null || exit.isBlank()) {
            return title;
        }
        // 否则返回站点名称和出口名称
        return title + "（" + exit + "）";
    }

    /** 公交和地铁票价单位是分，铁路是元。 */
    private String formatPrice(String vehicle, int price) {
        if (price < 0) {
            return "未知";
        }
        // 铁路
        if ("RAIL".equalsIgnoreCase(vehicle)) {
            return price + "元";
        }
        // 公交或地铁
        if (price % 100 == 0) {
            return (price / 100) + "元";
        }
        return String.format("%.2f元", price / 100.0);
    }

    /** 获取运行状态 */
    private String runningStatus(int status) {
        // 根据状态返回运行状态
        return switch (status) {
            case 301 -> "可能错过末班车";
            case 302 -> "首班车还未发出";
            case 303 -> "停运";
            default -> "";
        };
    }

    /** 如果线路名称为空，则返回未知线路 */
    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "未知线路" : value;
    }
}
