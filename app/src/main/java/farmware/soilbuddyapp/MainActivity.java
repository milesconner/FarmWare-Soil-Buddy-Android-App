package farmware.soilbuddyapp;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.tabs.TabLayout;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {
    private static final String DATA_TOPIC = "soil-buddy-prototype/data";
    private static final String SAMPLING_INTERVAL_TOPIC = "soil-buddy-prototype/sampling-interval";
    private static final int MAX_SAMPLING_INTERVAL_MS = 65_535;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    // Temporary comparison points for the NPK field test. Replace after calibration.
    private static final double NPK_LOW_MAX = 100.0;
    private static final double NPK_MEDIUM_MAX = 300.0;

    private static final String[] SECTION_TABS = {"Home", "Temperature", "Moisture", "Air Quality", "NPK", "Configuration"};
    private static final int[] RANGE_HOURS = {24, 24 * 7, 24 * 31, 24 * 365};
    private static final String[] RANGE_LABELS = {"24 hours", "7 days", "31 days", "365 days"};
    private static final int[] GROUP_COLORS = {0xFF4F7651, 0xFF698447, 0xFF507C82, 0xFF9A7A48};

    private static class MetricDefinition {
        final String key;
        final String title;
        final String unit;
        final Float minimum;
        final Float maximum;
        final int color;

        MetricDefinition(String key, String title, String unit, Float minimum, Float maximum, int color) {
            this.key = key;
            this.title = title;
            this.unit = unit;
            this.minimum = minimum;
            this.maximum = maximum;
            this.color = color;
        }
    }

    private static class ActiveGraph {
        final String metricKey;
        final MoistureChartView chart;

        ActiveGraph(String metricKey, MoistureChartView chart) {
            this.metricKey = metricKey;
            this.chart = chart;
        }
    }

    private Mqtt3AsyncClient client;
    private boolean mqttConnected;
    private TabLayout sectionTabs;
    private TabLayout rangeTabs;
    private FrameLayout contentFrame;
    private View homeView;
    private TextView sectionRangeCaption;
    private TextView homeReadingTime;
    private TextView connectionStatus;
    private TextView moisturePercent;
    private TextView moistureSummary;
    private TextView rawMoisture;
    private TextView airTemperature;
    private TextView humidity;
    private TextView airQuality;
    private TextView co2;
    private TextView vpd;
    private TextView probeTemperature;
    private TextView accuracy;
    private TextView nitrogen;
    private TextView phosphorus;
    private TextView potassium;
    private TextView nitrogenLevel;
    private TextView phosphorusLevel;
    private TextView potassiumLevel;
    private ProgressBar moistureProgress;
    private EditText nextBigCycleInput;
    private EditText samplingIntervalInput;
    private EditText stabilizingSamplesInput;
    private EditText actualSamplesInput;
    private TextView configMessage;
    private TelemetryHistoryStore historyStore;
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor();
    private final List<ActiveGraph> activeGraphs = new ArrayList<>();
    private String selectedSection = "Home";
    private int selectedRangeIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        sectionTabs = findViewById(R.id.section_tabs);
        rangeTabs = findViewById(R.id.range_tabs);
        contentFrame = findViewById(R.id.tab_content);
        historyStore = new TelemetryHistoryStore(this);
        createHomeDashboard();
        createNavigation();
        maintainHistory();
        connectMqtt();
    }

    private void createHomeDashboard() {
        homeView = getLayoutInflater().inflate(R.layout.home_dashboard, contentFrame, false);
        connectionStatus = homeView.findViewById(R.id.connection_status);
        homeReadingTime = homeView.findViewById(R.id.data_time_text);
        moisturePercent = homeView.findViewById(R.id.moisture_percent_text);
        moistureSummary = homeView.findViewById(R.id.moisture_summary_text);
        rawMoisture = homeView.findViewById(R.id.stemma_moisture_text);
        airTemperature = homeView.findViewById(R.id.bme_temp_text);
        humidity = homeView.findViewById(R.id.bme_humid_text);
        airQuality = homeView.findViewById(R.id.bme_iaq_text);
        co2 = homeView.findViewById(R.id.bme_co2_text);
        vpd = homeView.findViewById(R.id.bme_vpd_text);
        probeTemperature = homeView.findViewById(R.id.probe_temp_text);
        accuracy = homeView.findViewById(R.id.bme_acc_text);
        nitrogen = homeView.findViewById(R.id.nitrogen_text);
        phosphorus = homeView.findViewById(R.id.phosphorus_text);
        potassium = homeView.findViewById(R.id.potassium_text);
        nitrogenLevel = homeView.findViewById(R.id.nitrogen_level_text);
        phosphorusLevel = homeView.findViewById(R.id.phosphorus_level_text);
        potassiumLevel = homeView.findViewById(R.id.potassium_level_text);
        moistureProgress = homeView.findViewById(R.id.moisture_progress);
    }

    private void createNavigation() {
        for (String section : SECTION_TABS) sectionTabs.addTab(sectionTabs.newTab().setText(section));
        for (String range : RANGE_LABELS) rangeTabs.addTab(rangeTabs.newTab().setText(range));

        sectionTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showSection(SECTION_TABS[tab.getPosition()]); }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        rangeTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                selectedRangeIndex = tab.getPosition();
                for (ActiveGraph graph : activeGraphs) graph.chart.setRangeHours(RANGE_HOURS[selectedRangeIndex]);
                if (sectionRangeCaption != null) {
                    sectionRangeCaption.setText("Trends from readings received on this device · " + RANGE_LABELS[selectedRangeIndex]);
                }
                refreshActiveGraphs();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        sectionTabs.selectTab(sectionTabs.getTabAt(0));
        showSection("Home");
    }

    private void showSection(String section) {
        selectedSection = section;
        contentFrame.removeAllViews();
        activeGraphs.clear();
        if ("Home".equals(section)) {
            rangeTabs.setVisibility(View.GONE);
            contentFrame.addView(homeView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if ("Configuration".equals(section)) {
            rangeTabs.setVisibility(View.GONE);
            contentFrame.addView(createConfigurationScreen());
        } else {
            rangeTabs.setVisibility(View.VISIBLE);
            contentFrame.addView(createGraphScreen(section));
            refreshActiveGraphs();
        }
    }

    private View createGraphScreen(String section) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(page);

        TextView title = text(section.toUpperCase(Locale.getDefault()), 12, 0xFF607060, true);
        title.setLetterSpacing(0.08f);
        page.addView(title);
        TextView caption = text("Trends from readings received on this device · " + RANGE_LABELS[selectedRangeIndex], 13, 0xFF778477, false);
        sectionRangeCaption = caption;
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(-1, -2);
        captionParams.topMargin = dp(5);
        page.addView(caption, captionParams);

        int color = colorForSection(section);
        for (MetricDefinition metric : metricsForSection(section)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(16), dp(16), dp(14));
            card.setBackgroundResource(R.drawable.card_surface);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.topMargin = dp(14);
            page.addView(card, cardParams);

            TextView metricTitle = text(metric.title, 16, 0xFF263326, true);
            card.addView(metricTitle);
            TextView unit = text(metric.unit.isEmpty() ? "Raw sensor value" : "Unit: " + metric.unit, 12, 0xFF778477, false);
            LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(-1, -2);
            unitParams.topMargin = dp(3);
            card.addView(unit, unitParams);

            MoistureChartView chart = new MoistureChartView(this);
            chart.setRangeHours(RANGE_HOURS[selectedRangeIndex]);
            chart.setLineColor(metric.color);
            chart.setYAxisBounds(metric.minimum, metric.maximum);
            LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(-1, dp(192));
            chartParams.topMargin = dp(8);
            card.addView(chart, chartParams);
            activeGraphs.add(new ActiveGraph(metric.key, chart));
        }
        return scroll;
    }

    private MetricDefinition[] metricsForSection(String section) {
        if ("Temperature".equals(section)) return new MetricDefinition[]{
                metric("bme_temp", "Air temperature", "°C", null, null, GROUP_COLORS[0]),
                metric("probe_temp", "Soil probe temperature", "°C", null, null, 0xFF8A9B58)
        };
        if ("Moisture".equals(section)) return new MetricDefinition[]{
                metric("stemma_moisture_percent", "Soil moisture", "%", 0f, 100f, GROUP_COLORS[1]),
                metric("stemma_raw_moisture", "Raw capacitance", "raw count", null, null, 0xFF8A9B58)
        };
        if ("Air Quality".equals(section)) return new MetricDefinition[]{
                metric("bme_humid", "Relative humidity", "%", 0f, 100f, GROUP_COLORS[2]),
                metric("bme_iaq", "Air quality index", "IAQ", null, null, 0xFF6B9A92),
                metric("bme_co2", "CO₂ concentration", "ppm", 0f, null, 0xFF527D8D),
                metric("bme_vpd", "Vapor pressure deficit", "Pa", 0f, null, 0xFF66849B),
                metric("bme_acc", "Air sensor accuracy", "0–3", 0f, 3f, 0xFF8A9B58)
        };
        if ("NPK".equals(section)) return new MetricDefinition[]{
                metric("npk_raw_nit", "Nitrogen (N)", "raw value", null, null, GROUP_COLORS[3]),
                metric("npk_raw_phos", "Phosphorus (P)", "raw value", null, null, 0xFFB28E59),
                metric("npk_raw_pot", "Potassium (K)", "raw value", null, null, 0xFF8D754A)
        };
        return new MetricDefinition[0];
    }

    private MetricDefinition metric(String key, String title, String unit, Float minimum, Float maximum, int color) {
        return new MetricDefinition(key, title, unit, minimum, maximum, color);
    }

    private int colorForSection(String section) {
        if ("Temperature".equals(section)) return GROUP_COLORS[0];
        if ("Moisture".equals(section)) return GROUP_COLORS[1];
        if ("Air Quality".equals(section)) return GROUP_COLORS[2];
        return GROUP_COLORS[3];
    }

    private void refreshActiveGraphs() {
        if (historyStore == null || activeGraphs.isEmpty()) return;
        final List<ActiveGraph> graphs = new ArrayList<>(activeGraphs);
        long since = System.currentTimeMillis() - RANGE_HOURS[selectedRangeIndex] * 60L * 60L * 1000L;
        historyExecutor.execute(() -> {
            Map<ActiveGraph, List<MoistureChartView.Point>> result = new HashMap<>();
            for (ActiveGraph graph : graphs) result.put(graph, historyStore.getSince(graph.metricKey, since));
            runOnUiThread(() -> {
                for (Map.Entry<ActiveGraph, List<MoistureChartView.Point>> entry : result.entrySet()) {
                    entry.getKey().chart.setPoints(entry.getValue());
                }
            });
        });
    }

    private View createConfigurationScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(page);

        TextView heading = text("CONFIGURATION", 12, 0xFF607060, true);
        heading.setLetterSpacing(0.08f);
        page.addView(heading);
        TextView title = text("Sensor cycle", 27, 0xFF1D2A1D, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(5);
        page.addView(title, titleParams);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundResource(R.drawable.card_surface);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(20);
        page.addView(card, cardParams);

        TextView wakeHeading = text("Wake cycle", 17, 0xFF263326, true);
        card.addView(wakeHeading);
        nextBigCycleInput = addConfigurationField(card, "Next big cycle", "Wake-cycle number passed to the sensor.", "next_big_cycle", "1");

        TextView samplingHeading = text("Sampling while awake", 17, 0xFF263326, true);
        LinearLayout.LayoutParams samplingHeadingParams = new LinearLayout.LayoutParams(-1, -2);
        samplingHeadingParams.topMargin = dp(22);
        card.addView(samplingHeading, samplingHeadingParams);
        samplingIntervalInput = addConfigurationField(card, "Time between samples (milliseconds)", "Interval between individual samples during a wake period. Range: 1–65,535 ms.", "sampling_interval_ms", "1000");
        stabilizingSamplesInput = addConfigurationField(card, "Stabilizing samples", "Readings discarded while sensors settle before the measured samples.", "stabilizing_samples", "3");
        actualSamplesInput = addConfigurationField(card, "Actual samples", "Number of measured samples collected for a reading.", "actual_samples", "12");

        TextView limit = text("The interval is not the sleep duration or time until the next wake. Longer sleep schedules are determined by the sensor firmware's wake-cycle behavior.", 12, 0xFF8B6B38, false);
        LinearLayout.LayoutParams limitParams = new LinearLayout.LayoutParams(-1, -2);
        limitParams.topMargin = dp(10);
        card.addView(limit, limitParams);

        Button save = new Button(this);
        save.setText("Send settings to gateway");
        save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        save.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#355B3A")));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(48));
        saveParams.topMargin = dp(16);
        card.addView(save, saveParams);

        configMessage = text("", 13, 0xFF4F7651, false);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
        messageParams.topMargin = dp(12);
        card.addView(configMessage, messageParams);
        save.setOnClickListener(view -> publishConfiguration());
        return scroll;
    }

    private EditText addConfigurationField(LinearLayout parent, String title, String helper,
                                           String preferenceKey, String defaultValue) {
        TextView label = text(title, 14, 0xFF344334, true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
        labelParams.topMargin = dp(13);
        parent.addView(label, labelParams);

        TextView description = text(helper, 12, 0xFF778477, false);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(3);
        parent.addView(description, descriptionParams);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(getSharedPreferences("soil_buddy_config", MODE_PRIVATE)
                .getString(preferenceKey, defaultValue));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, dp(52));
        inputParams.topMargin = dp(4);
        parent.addView(input, inputParams);
        return input;
    }

    private void publishConfiguration() {
        String nextCycle = nextBigCycleInput.getText().toString().trim();
        String interval = samplingIntervalInput.getText().toString().trim();
        String stabilizing = stabilizingSamplesInput.getText().toString().trim();
        String actual = actualSamplesInput.getText().toString().trim();
        if (!isInRange(nextCycle, 1, 65_535)
                || !isInRange(interval, 1, MAX_SAMPLING_INTERVAL_MS)
                || !isInRange(stabilizing, 0, 255)
                || !isInRange(actual, 1, 255)) {
            configMessage.setText("Check the ranges: next cycle 1–65,535; interval 1–65,535 ms; stabilizing samples 0–255; actual samples 1–255.");
            configMessage.setTextColor(Color.parseColor("#A43F35"));
            return;
        }
        if (!mqttConnected || client == null) {
            configMessage.setText("The MQTT connection is not ready yet.");
            configMessage.setTextColor(Color.parseColor("#A43F35"));
            return;
        }

        String[][] settings = {
                {"soil-buddy-prototype/next-big-cycle", nextCycle},
                {SAMPLING_INTERVAL_TOPIC, interval},
                {"soil-buddy-prototype/stabilizing-samples", stabilizing},
                {"soil-buddy-prototype/actual-samples", actual}
        };
        AtomicInteger remaining = new AtomicInteger(settings.length);
        AtomicBoolean failed = new AtomicBoolean(false);
        for (String[] setting : settings) {
            client.publishWith()
                    .topic(setting[0])
                    .payload(setting[1].getBytes(StandardCharsets.UTF_8))
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .send()
                    .whenComplete((publishResult, error) -> {
                        if (error != null) failed.set(true);
                        if (remaining.decrementAndGet() == 0) {
                            runOnUiThread(() -> {
                                if (failed.get()) {
                                    configMessage.setText("One or more settings could not be published. Check the MQTT connection and retry.");
                                    configMessage.setTextColor(Color.parseColor("#A43F35"));
                                } else {
                                    getSharedPreferences("soil_buddy_config", MODE_PRIVATE).edit()
                                            .putString("next_big_cycle", nextCycle)
                                            .putString("sampling_interval_ms", interval)
                                            .putString("stabilizing_samples", stabilizing)
                                            .putString("actual_samples", actual)
                                            .apply();
                                    configMessage.setText("Settings published. The sensor applies them during its wake cycle.");
                                    configMessage.setTextColor(Color.parseColor("#4F7651"));
                                }
                            });
                        }
                    });
        }
    }

    private boolean isInRange(String value, int minimum, int maximum) {
        try {
            int number = Integer.parseInt(value);
            return number >= minimum && number <= maximum;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private TextView text(String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private void maintainHistory() {
        historyExecutor.execute(() -> {
            historyStore.maintainHistory(System.currentTimeMillis());
        });
    }

    private void connectMqtt() {
        client = MqttClient.builder()
                .useMqttVersion3()
                .identifier("soil-buddy-android-app")
                .serverHost("9ccaa78e62aa4522ab541b0ca1426b5c.s1.eu.hivemq.cloud")
                .serverPort(8883)
                .useSslWithDefaultConfig()
                .buildAsync();

        client.connectWith()
                .simpleAuth()
                .username("SoilBuddyPrototype")
                .password("FarmWareTopSecretPassword12".getBytes(StandardCharsets.UTF_8))
                .applySimpleAuth()
                .send()
                .whenComplete((connAck, error) -> runOnUiThread(() -> {
                    if (error != null) {
                        mqttConnected = false;
                        setConnectionStatus("Connection unavailable", false);
                    } else {
                        mqttConnected = true;
                        setConnectionStatus("Connected · waiting for sensor", true);
                        subscribeToTelemetry();
                    }
                }));
    }

    private void subscribeToTelemetry() {
        client.subscribeWith()
                .topicFilter(DATA_TOPIC)
                .callback(publish -> {
                    String topic = publish.getTopic().toString();
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
                    runOnUiThread(() -> renderPayload(topic, payload));
                })
                .send()
                .whenComplete((subAck, error) -> runOnUiThread(() -> {
                    if (error != null) setConnectionStatus("Connected · subscription failed", false);
                }));
    }

    private void renderPayload(String topic, String payload) {
        try {
            if (payload.trim().startsWith("{")) {
                renderJson(new JSONObject(payload));
            } else {
                renderScalar(topic, payload.trim());
            }
            setConnectionStatus("Live sensor data", true);
        } catch (JSONException exception) {
            setConnectionStatus("Received unreadable data", false);
        }
    }

    private void renderJson(JSONObject packet) {
        setMetric(packet, airTemperature, "°C", "bme_temp", "bmeTemp");
        setMetric(packet, humidity, "%", "bme_humid", "bmeHumid");
        setMetric(packet, airQuality, " IAQ", "bme_iaq", "bmeIaq");
        setMetric(packet, co2, " ppm", "bme_co2", "bmeCo2");
        setMetric(packet, vpd, " Pa", "bme_vpd", "bmeVpd");
        setMetric(packet, probeTemperature, "°C", "probe_temp", "probeTemperature");
        setMetric(packet, rawMoisture, "", "stemma_raw_moisture", "stemma_moisture");
        setMetric(packet, accuracy, "", "bme_acc", "bmeAcc");
        setNpkMetric(packet, nitrogen, nitrogenLevel, "Nitrogen", "npk_raw_nit");
        setNpkMetric(packet, phosphorus, phosphorusLevel, "Phosphorus", "npk_raw_phos");
        setNpkMetric(packet, potassium, potassiumLevel, "Potassium", "npk_raw_pot");

        String percent = find(packet, "stemma_moisture_percent", "moisture_percent", "moisturePercentage");
        if (percent != null) setMoisturePercent(percent);
        String timestamp = find(packet, "data_time", "timestamp", "time", "dataTime");
        Long timeMillis = null;
        if (timestamp != null) {
            homeReadingTime.setText("Updated " + timestamp);
            timeMillis = parseGatewayTimestamp(timestamp);
        }
        if (timeMillis != null) {
            Map<String, Double> values = new HashMap<>();
            putNumeric(values, "bme_temp", find(packet, "bme_temp"));
            putNumeric(values, "probe_temp", find(packet, "probe_temp"));
            putNumeric(values, "stemma_moisture_percent", percent);
            putNumeric(values, "stemma_raw_moisture", find(packet, "stemma_raw_moisture"));
            putNumeric(values, "bme_humid", find(packet, "bme_humid"));
            putNumeric(values, "bme_iaq", find(packet, "bme_iaq"));
            putNumeric(values, "bme_co2", find(packet, "bme_co2"));
            putNumeric(values, "bme_vpd", find(packet, "bme_vpd"));
            putNumeric(values, "bme_acc", find(packet, "bme_acc"));
            putNumeric(values, "npk_raw_nit", find(packet, "npk_raw_nit"));
            putNumeric(values, "npk_raw_phos", find(packet, "npk_raw_phos"));
            putNumeric(values, "npk_raw_pot", find(packet, "npk_raw_pot"));
            if (!values.isEmpty()) saveTelemetryPacket(timeMillis, values);
        }
    }

    private void saveTelemetryPacket(long timestampMillis, Map<String, Double> values) {
        historyExecutor.execute(() -> {
            historyStore.recordPacket(timestampMillis, values);
            runOnUiThread(() -> {
                refreshActiveGraphs();
            });
        });
    }

    private void putNumeric(Map<String, Double> values, String key, String value) {
        if (value == null) return;
        try {
            double number = Double.parseDouble(value);
            if (!Double.isNaN(number) && !Double.isInfinite(number)) values.put(key, number);
        } catch (NumberFormatException ignored) {
            // Non-numeric packet fields remain visible on the dashboard but are not charted.
        }
    }

    private void renderScalar(String topic, String value) {
        String key = topic.substring(topic.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        switch (key) {
            case "bme-temp": airTemperature.setText(value + " °C"); break;
            case "bme-humid": humidity.setText(value + " %"); break;
            case "bme-iaq": airQuality.setText(value + " IAQ"); break;
            case "bme-co2": co2.setText(value + " ppm"); break;
            case "bme-vpd": vpd.setText(value + " Pa"); break;
            case "probe-temp": probeTemperature.setText(value + " °C"); break;
            case "stemma-moisture": rawMoisture.setText("Raw capacitance: " + value); break;
            case "bme-acc": accuracy.setText("Sensor accuracy: " + value + " / 3"); break;
            case "data-time": homeReadingTime.setText("Updated " + value); break;
            case "npk-raw-nit": setNpkScalar(nitrogen, nitrogenLevel, "Nitrogen", value); break;
            case "npk-raw-phos": setNpkScalar(phosphorus, phosphorusLevel, "Phosphorus", value); break;
            case "npk-raw-pot": setNpkScalar(potassium, potassiumLevel, "Potassium", value); break;
            case "moisture-percent": setMoisturePercent(value); break;
            default: break;
        }
    }

    private void setNpkScalar(TextView rawView, TextView levelView, String name, String value) {
        rawView.setText("Raw: " + value);
        try {
            double number = Double.parseDouble(value);
            String level = number <= NPK_LOW_MAX ? "Low" : number <= NPK_MEDIUM_MAX ? "Medium" : "High";
            levelView.setText(level + " · placeholder threshold");
        } catch (NumberFormatException ignored) {
            levelView.setText(name + " value unavailable for comparison");
        }
    }

    private void setMetric(JSONObject packet, TextView target, String suffix, String... keys) {
        String value = find(packet, keys);
        if (value == null) return;
        String label = target == rawMoisture ? "Raw capacitance: " : "";
        if (target == accuracy) label = "Sensor accuracy: ";
        String unit = target == accuracy ? " / 3" : suffix;
        target.setText(label + value + unit);
    }

    private void setNpkMetric(JSONObject packet, TextView rawView, TextView levelView,
                              String nutrient, String... keys) {
        String rawValue = find(packet, keys);
        if (rawValue == null) return;
        setNpkScalar(rawView, levelView, nutrient, rawValue);
        try {
            double value = Double.parseDouble(rawValue);
            String level = value <= NPK_LOW_MAX ? "Low" : value <= NPK_MEDIUM_MAX ? "Medium" : "High";
            levelView.setTextColor(Color.parseColor(
                    "Low".equals(level) ? "#A36A3C" : "Medium".equals(level) ? "#A17A21" : "#4F7651"));
        } catch (NumberFormatException ignored) {
            levelView.setTextColor(Color.parseColor("#708070"));
        }
    }

    private String find(JSONObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.isNull(key)) return object.optString(key, null);
        }
        return null;
    }

    private Long parseGatewayTimestamp(String value) {
        String trimmed = value.trim();
        String[] formats = {
                "MM/dd/yyyy hh:mm:ss a",
                "yyyy-MM-dd'T'HH:mm:ss.SSSX",
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd HH:mm:ss"
        };
        for (String format : formats) {
            SimpleDateFormat parser = new SimpleDateFormat(format, Locale.US);
            parser.setLenient(false);
            if (format.startsWith("MM/")) parser.setTimeZone(TimeZone.getTimeZone("America/New_York"));
            ParsePosition position = new ParsePosition(0);
            Date parsed = parser.parse(trimmed, position);
            if (parsed != null && position.getIndex() == trimmed.length()) return parsed.getTime();
        }
        return null;
    }

    private void setMoisturePercent(String value) {
        try {
            int percent = Math.max(0, Math.min(100, (int) Math.round(Double.parseDouble(value))));
            moisturePercent.setText(percent + "%");
            moistureProgress.setProgress(percent);
            moistureSummary.setText(percent < 25 ? "Soil is on the dry side" : percent > 80 ? "Soil is very moist" : "Moisture is in a healthy range");
        } catch (NumberFormatException ignored) {
            moisturePercent.setText(value + "%");
            moistureSummary.setText("Latest soil moisture reading");
        }
    }

    private void setConnectionStatus(String message, boolean connected) {
        if (connectionStatus == null) return;
        connectionStatus.setText(message);
        homeView.findViewById(R.id.connection_dot).setBackgroundTintList(ColorStateList.valueOf(
                Color.parseColor(connected ? "#4F9659" : "#D99A36")));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (client != null) client.disconnect();
        historyExecutor.shutdown();
        if (historyStore != null) historyStore.close();
        super.onDestroy();
    }
}
