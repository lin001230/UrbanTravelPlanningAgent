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
 * 调用腾讯公交路线接口。先留下卡片要用的路线结构，再根据这份结构生成给模型看的文字。
 * MCP 的 directionTransit 只返回「经过几站」，途经站名以这里的 stations 为准。
 */
public class TransitRouteTool {

    /** 与 @Tool 方法名一致。 */
    public static final String NAME = "planTransit";

    private static final String TRANSIT_API_URL = "https://apis.map.qq.com/ws/direction/v1/transit/";

    private static final int TIMEOUT_MILLIS = 15000;

    /** 一次最多交给模型的方案数，避免把全部备选都塞进上下文。 */
    private static final int MAX_ROUTES = 3;

    /**
     * 本轮线程里最后一次成功的方案。工具实例是共享的，不能放在字段上。
     * 同一次请求的工具调用和写最终方案发生在同一个虚拟线程里。
     */
    private static final ThreadLocal<List<RoutePlan>> CURRENT = new ThreadLocal<>();

    /**
     * 查询起终点之间的途经站。失败时返回给模型可读的说明，不向外抛出。
     */
    @Tool(description = "根据起点和终点坐标查询公共交通途经站。返回每条方案的线路、上下车站，以及按乘坐顺序排列的途经站名。站名只从这里抄，不要自行补充。")
    public String planTransit(
            @ToolParam(description = "起点坐标，纬度在前、经度在后，例如 22.445,114.035") String from,
            @ToolParam(description = "终点坐标，纬度在前、经度在后，例如 22.337,114.140") String to) {
        // 验证起点坐标
        String fromError = validateCoordinate(from, "起点");
        if (fromError != null) {
            return fromError;
        }
        // 验证终点坐标
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
            // 解析路线
            Parsed parsed = parseRoutes(JSONUtil.parseObj(responseBody));
            // 如果方案不为空，则设置当前方案
            if (!parsed.plans().isEmpty()) {
                CURRENT.set(parsed.plans());
            }
            // 返回解析结果
            return parsed.text();
        } catch (Exception e) {
            String message = e.getMessage();
            return "planTransit error：" + (message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
        }
    }

    /** 本轮最后一次成功查询的方案。还没查到时为空列表。 */
    public static List<RoutePlan> currentPlans() {
        // 获取当前方案
        List<RoutePlan> plans = CURRENT.get();
        // 如果方案为空，则返回空列表
        return plans == null ? List.of() : plans;
    }

    /** 请求线程结束前清掉，避免虚拟线程复用时带上下一轮的路线。 */
    public static void clearCurrentPlans() {
        // 清空当前方案
        CURRENT.remove();
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

    /** 解析路线 */
    private Parsed parseRoutes(JSONObject json) {
        // 获取状态码
        int status = json.getInt("status", -1);
        // 如果状态码不是 0，则返回错误信息
        if (status != 0) {
            String message = json.getStr("message");
            return Parsed.error("planTransit error：" + status + (message == null || message.isBlank() ? "" : " " + message));
        }
        // 获取路线
        JSONArray routes = json.getByPath("result.routes", JSONArray.class);
        // 如果路线为空，则返回错误信息
        if (routes == null || routes.isEmpty()) {
            return Parsed.error("没有查到公共交通方案");
        }
        // 获取最大方案数
        int count = Math.min(routes.size(), MAX_ROUTES);
        // 创建方案列表
        List<RoutePlan> plans = new ArrayList<>(count);
        // 遍历方案
        for (int i = 0; i < count; i++) {
            // 解析并添加单个方案
            plans.add(parseRoute((JSONObject) routes.get(i)));
        }
        // 返回解析结果
        return new Parsed(plans, renderPlans(plans, routes.size()));
    }

    /** 解析单个方案 */
    private RoutePlan parseRoute(JSONObject route) {
        // 获取步骤
        JSONArray steps = route.getJSONArray("steps");
        // 创建步骤列表
        List<Leg> legs = new ArrayList<>();
        // 初始化步行距离
        int walkingMeters = 0;
        if (steps != null) {
            // 遍历步骤
            for (Object item : steps) {
                JSONObject step = (JSONObject) item;
                String mode = step.getStr("mode");
                // 如果是步行，则添加步行距离和步行时间
                if ("WALKING".equalsIgnoreCase(mode)) {
                    int distance = step.getInt("distance", 0);
                    walkingMeters += distance;
                    legs.add(Leg.walk(distance, step.getInt("duration", 0)));
                } else if ("TRANSIT".equalsIgnoreCase(mode)) {
                    // 如果是公交或地铁，则解析公交或地铁段
                    // 并添加到步骤列表
                    legs.add(parseRide(step));
                }
            }
        }
        // 返回解析结果
        return new RoutePlan(route.getInt("duration", 0), route.getInt("distance", 0), walkingMeters, List.copyOf(legs));
    }

    /** 同一段可能有多条并行线路，完整站名在第一条。 */
    private Leg parseRide(JSONObject step) {
        // 获取线路
        JSONArray lines = step.getJSONArray("lines");
        if (lines == null || lines.isEmpty()) {
            return Leg.ride(0, "", "", "", "", "", "", List.of(), "", "", List.of());
        }
        // 获取第一条线路
        JSONObject line = (JSONObject) lines.get(0);
        // 获取上车地点
        Stop board = stop(line.getJSONObject("geton"));
        // 获取下车地点
        Stop alight = stop(line.getJSONObject("getoff"));
        // 创建备选线路列表
        List<String> alternatives = new ArrayList<>();
        // 遍历备选线路
        for (int i = 1; i < lines.size(); i++) {
            // 获取备选线路名称
            String title = ((JSONObject) lines.get(i)).getStr("title");
            // 如果备选线路名称不为空，则添加到备选线路列表
            if (title != null && !title.isBlank()) {
                alternatives.add(title);
            }
        }
        // 返回解析结果
        return Leg.ride(
                line.getInt("duration", 0),
                blankToEmpty(line.getStr("title")),
                blankToEmpty(line.getByPath("destination.title", String.class)),
                board.name(),
                board.exit(),
                alight.name(),
                alight.exit(),
                stationNames(line.getJSONArray("stations")),
                formatPrice(line.getStr("vehicle"), line.getInt("price", -1)),
                runningStatus(line.getInt("running_status", 300)),
                List.copyOf(alternatives));
    }

    /** 获取途经站名 */
    private List<String> stationNames(JSONArray stations) {
        // 如果途经站点为空，则返回空列表
        if (stations == null || stations.isEmpty()) {
            return List.of();
        }
        // 创建途经站点列表
        List<String> names = new ArrayList<>();
        // 遍历途经站点
        for (Object item : stations) {
            String title = ((JSONObject) item).getStr("title");
            // 如果途经站点名称不为空，则添加到途经站点列表
            if (title != null && !title.isBlank()) {
                names.add(title);
            }
        }
        // 返回途经站点列表
        return List.copyOf(names);
    }

    /** 获取上下车站 */
    private Stop stop(JSONObject place) {
        // 如果上下车站为空，则返回空站点
        if (place == null) {
            return new Stop("", "");
        }
        return new Stop(blankToEmpty(place.getStr("title")), blankToEmpty(place.getByPath("exit.title", String.class)));
    }

    /**
     * 把路线结构写成给模型看的文字。
     * plans 是实际列出的方案，total 是接口返回的总数，多出来的只说明未列出。
     */
    private String renderPlans(List<RoutePlan> plans, int total) {
        // 创建文本
        StringBuilder text = new StringBuilder();
        // 添加方案数量
        text.append("公共交通方案共 ").append(plans.size()).append(" 条。途经站只准照抄下列名称，没有出现的站不要补充。");
        // 如果总方案数大于实际列出的方案数，则添加未列出的方案数
        if (total > plans.size()) {
            text.append("其余 ").append(total - plans.size()).append(" 条未列出。");
        }
        // 遍历方案
        for (int i = 0; i < plans.size(); i++) {
            // 添加方案
            text.append("\n\n方案").append(i + 1).append("：").append(renderPlan(plans.get(i)));
        }
        // 返回文本
        return text.toString();
    }

    /** 将单个方案解析为文字 */
    private String renderPlan(RoutePlan plan) {
        // 创建文本
        StringBuilder text = new StringBuilder();
        text.append("约 ").append(plan.durationMinutes()).append(" 分钟，总距离 ")
                .append(plan.distanceMeters()).append(" 米，步行 ").append(plan.walkingMeters()).append(" 米");
        // 遍历步骤
        for (Leg leg : plan.legs()) {
            // 添加步骤
            text.append("\n- ").append(renderLeg(leg));
        }
        return text.toString();
    }

    /** 将单个步骤解析为文字 */
    private String renderLeg(Leg leg) {
        // 如果是步行，则返回步行距离和步行时间
        if ("walk".equals(leg.mode())) {
            return "步行 " + leg.distanceMeters() + " 米，约 " + leg.durationMinutes() + " 分钟";
        }
        // 如果线路和上车地点为空，则返回错误信息
        if (leg.line().isEmpty() && leg.board().isEmpty()) {
            return "公共交通段没有返回线路";
        }
        StringBuilder text = new StringBuilder();
        // 添加线路
        text.append("乘坐").append(leg.line().isEmpty() ? "未知线路" : leg.line());
        // 添加方向
        if (!leg.direction().isEmpty()) {
            text.append("，往").append(leg.direction());
        }
        text.append("。从").append(placeLabel(leg.board(), leg.boardExit())).append("上车");
        // 如果途经站点为空，则返回错误信息
        if (leg.stops().isEmpty()) {
            text.append("，途经站名未返回，不要编造");
        } else {
            // 添加途经站点
            text.append("，途经").append(String.join("、", leg.stops()));
        }
        text.append("，在").append(placeLabel(leg.alight(), leg.alightExit())).append("下车");
        text.append("。约 ").append(leg.durationMinutes()).append(" 分钟，费用 ")
                .append(leg.price().isEmpty() ? "未知" : leg.price());
        // 如果注意信息不为空，则添加注意信息
        if (!leg.notice().isEmpty()) {
            text.append("，").append(leg.notice());
        }
        // 如果备选线路不为空，则添加备选线路
        if (!leg.alternatives().isEmpty()) {
            text.append("。同段还可乘坐").append(String.join("、", leg.alternatives()));
        }
        return text.toString();
    }

    /** 获取地点标签 */
    private String placeLabel(String name, String exit) {
        String title = name.isEmpty() ? "未知站点" : name;
        return exit.isEmpty() ? title : title + "（" + exit + "）";
    }

    /** 公交和地铁票价单位是分，铁路是元。 */
    private String formatPrice(String vehicle, int price) {
        if (price < 0) {
            return "";
        }
        // 交通工具为铁路
        if ("RAIL".equalsIgnoreCase(vehicle)) {
            return price + "元";
        }
        // 交通工具为公交或地铁
        if (price % 100 == 0) {
            return (price / 100) + "元";
        }
        return String.format("%.2f元", price / 100.0);
    }

    /** 获取运行状态 */
    private String runningStatus(int status) {
        return switch (status) {
            case 301 -> "可能错过末班车";
            case 302 -> "首班车还未发出";
            case 303 -> "停运";
            default -> "";
        };
    }

    private String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    /** 解析结果 */
    private record Parsed(List<RoutePlan> plans, String text) {
        /** 创建解析错误结果 */
        private static Parsed error(String text) {
            return new Parsed(List.of(), text);
        }
    }

    private record Stop(String name, String exit) {
    }

    /** 一条公共交通方案。legs 按出发顺序排列，步行段和乘坐段交替出现。 */
    public record RoutePlan(int durationMinutes, int distanceMeters, int walkingMeters, List<Leg> legs) {
    }

    /**
     * 方案中的一段。mode 为 walk 或 ride。
     * 步行段只使用距离和时长；乘坐段的途经站名在 stops 里，不含上下车站。
     */
    public record Leg(
            String mode,
            int distanceMeters,
            int durationMinutes,
            String line,
            String direction,
            String board,
            String boardExit,
            String alight,
            String alightExit,
            List<String> stops,
            String price,
            String notice,
            List<String> alternatives) {

        /** 创建步行段 */
        private static Leg walk(int distanceMeters, int durationMinutes) {
            return new Leg("walk", distanceMeters, durationMinutes, "", "", "", "", "", "", List.of(), "", "", List.of());
        }

        /** 创建乘坐段 */
        private static Leg ride(int durationMinutes, String line, String direction, String board, String boardExit,
                                String alight, String alightExit, List<String> stops, String price,
                                String notice, List<String> alternatives) {
            return new Leg("ride", 0, durationMinutes, line, direction, board, boardExit, alight, alightExit,
                    stops, price, notice, alternatives);
        }
    }
}
