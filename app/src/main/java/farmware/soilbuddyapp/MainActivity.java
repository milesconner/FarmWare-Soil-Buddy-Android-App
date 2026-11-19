package farmware.soilbuddyapp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private static final String CACHE_PREFS = "soil_buddy_latest_telemetry";
    private static final long INITIAL_RETRY_DELAY_MS = 2_000L;
    private static final long MAX_RETRY_DELAY_MS = 30_000L;

    // Temporary, separate comparison points for the NPK field test. Replace after calibration.
    private static final NpkThreshold NITROGEN_THRESHOLDS = new NpkThreshold("npk_raw_nit", "Nitrogen", 100, 300);
    private static final NpkThreshold PHOSPHORUS_THRESHOLDS = new NpkThreshold("npk_raw_phos", "Phosphorus", 50, 150);
    private static final NpkThreshold POTASSIUM_THRESHOLDS = new NpkThreshold("npk_raw_pot", "Potassium", 150, 400);

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
        final NpkThreshold npkThreshold;
        final boolean categoricalLevel;

        MetricDefinition(String key, String title, String unit, Float minimum, Float maximum, int color,
                         NpkThreshold npkThreshold, boolean categoricalLevel) {
            this.key = key;
            this.title = title;
            this.unit = unit;
            this.minimum = minimum;
            this.maximum = maximum;
            this.color = color;
            this.npkThreshold = npkThreshold;
            this.categoricalLevel = categoricalLevel;
        }
    }

    private static class NpkThreshold {
        final String metricKey;
        final String nutrient;
        final double lowMaximum;
        final double mediumMaximum;

        NpkThreshold(String metricKey, String nutrient, double lowMaximum, double mediumMaximum) {
            this.metricKey = metricKey;
            this.nutrient = nutrient;
            this.lowMaximum = lowMaximum;
            this.mediumMaximum = mediumMaximum;
        }

        float category(double value) {
            if (value <= lowMaximum) return 0f;
            if (value <= mediumMaximum) return 1f;
            return 2f;
        }

        String categoryName(double value) {
            if (value <= lowMaximum) return "Low";
            if (value <= mediumMaximum) return "Medium";
            return "High";
        }

        String description() {
            return "Provisional: Low ≤" + formatThreshold(lowMaximum)
                    + ", Medium ≤" + formatThreshold(mediumMaximum)
                    + ", High >" + formatThreshold(mediumMaximum);
        }

        private String formatThreshold(double value) {
            String formatted = String.format(Locale.US, "%.2f", value);
            while (formatted.contains(".") && formatted.endsWith("0")) {
                formatted = formatted.substring(0, formatted.length() - 1);
            }
            if (formatted.endsWith(".")) formatted = formatted.substring(0, formatted.length() - 1);
            return formatted;
        }
    }

    private static class ActiveGraph {
        final MetricDefinition metric;
        final MoistureChartView chart;

        ActiveGraph(MetricDefinition metric, MoistureChartView chart) {
            this.metric = metric;
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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ActiveGraph> activeGraphs = new ArrayList<>();
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Runnable reconnectRunnable;
    private long reconnectDelayMs = INITIAL_RETRY_DELAY_MS;
    private long connectionAttemptId;
    private boolean mqttConnecting;
    private boolean mqttDisconnecting;
    private boolean hasCachedTelemetry;
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
        loadCachedTelemetry();
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
            String metricSubtitle = metric.npkThreshold != null
                    ? (metric.categoricalLevel ? metric.npkThreshold.description() : "Raw value sent by sensor · " + metric.unit)
                    : (metric.unit.isEmpty() ? "Raw sensor value" : "Unit: " + metric.unit);
            TextView unit = text(metricSubtitle, 12, 0xFF778477, false);
            LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(-1, -2);
            unitParams.topMargin = dp(3);
            card.addView(unit, unitParams);

            MoistureChartView chart = new MoistureChartView(this);
            chart.setRangeHours(RANGE_HOURS[selectedRangeIndex]);
            chart.setLineColor(metric.color);
            chart.setYAxisBounds(metric.minimum, metric.maximum);
            if (metric.categoricalLevel) chart.setCategoricalLabels("Low", "Medium", "High");
            LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(-1, dp(192));
            chartParams.topMargin = dp(8);
            card.addView(chart, chartParams);
            activeGraphs.add(new ActiveGraph(metric, chart));
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
                npkRaw(NITROGEN_THRESHOLDS, "Nitrogen (N)", GROUP_COLORS[3]),
                npkLevel(NITROGEN_THRESHOLDS, "Nitrogen level (provisional)", 0xFF78925D),
                npkRaw(PHOSPHORUS_THRESHOLDS, "Phosphorus (P)", 0xFFB28E59),
                npkLevel(PHOSPHORUS_THRESHOLDS, "Phosphorus level (provisional)", 0xFFC5A36B),
                npkRaw(POTASSIUM_THRESHOLDS, "Potassium (K)", 0xFF8D754A),
                npkLevel(POTASSIUM_THRESHOLDS, "Potassium level (provisional)", 0xFFAA946D)
        };
        return new MetricDefinition[0];
    }

    private MetricDefinition metric(String key, String title, String unit, Float minimum, Float maximum, int color) {
        return new MetricDefinition(key, title, unit, minimum, maximum, color, null, false);
    }

    private MetricDefinition npkRaw(NpkThreshold thresholds, String title, int color) {
        return new MetricDefinition(thresholds.metricKey, title, "raw value", null, null, color, thresholds, false);
    }

    private MetricDefinition npkLevel(NpkThreshold thresholds, String title, int color) {
        return new MetricDefinition(thresholds.metricKey, title, "status", 0f, 2f, color, thresholds, true);
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
            for (ActiveGraph graph : graphs) {
                List<MoistureChartView.Point> points = historyStore.getSince(graph.metric.key, since);
                if (graph.metric.categoricalLevel) {
                    List<MoistureChartView.Point> categories = new ArrayList<>(points.size());
                    for (MoistureChartView.Point point : points) {
                        categories.add(new MoistureChartView.Point(
                                point.timeMillis, graph.metric.npkThreshold.category(point.value)));
                    }
                    points = categories;
                }
                result.put(graph, points);
            }
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

    private void loadCachedTelemetry() {
        android.content.SharedPreferences preferences = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE);
        String payload = preferences.getString("payload", null);
        String topic = preferences.getString("topic", DATA_TOPIC);
        if (payload == null || payload.trim().isEmpty()) {
            setConnectionStatus("No saved reading yet", false);
            return;
        }
        hasCachedTelemetry = true;
        renderPayload(topic, payload, false);
    }

    private void connectMqtt() {
        client = MqttClient.builder()
                .useMqttVersion3()
                .identifier("soil-buddy-android-app")
                .serverHost("9ccaa78e62aa4522ab541b0ca1426b5c.s1.eu.hivemq.cloud")
                .serverPort(8883)
                .useSslWithDefaultConfig()
                .buildAsync();

        registerNetworkMonitor();
        if (hasValidatedNetwork()) attemptMqttConnection();
        else {
            setConnectionStatus(hasCachedTelemetry ? "Offline · showing last saved reading" : "Waiting for internet connection", false);
            scheduleReconnect();
        }
    }

    private void registerNetworkMonitor() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                mainHandler.post(() -> onNetworkAvailable());
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    mainHandler.post(() -> onNetworkAvailable());
                }
            }

            @Override
            public void onLost(Network network) {
                mainHandler.post(() -> onNetworkStateChanged());
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException exception) {
            networkCallback = null;
        }
    }

    private boolean hasValidatedNetwork() {
        if (connectivityManager == null) return false;
        Network active = connectivityManager.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void onNetworkAvailable() {
        if (!hasValidatedNetwork()) return;
        cancelScheduledReconnect();
        if (!mqttConnected) {
            setConnectionStatus(hasCachedTelemetry
                    ? "Network available · reconnecting, showing saved reading"
                    : "Network available · connecting", false);
            attemptMqttConnection();
        }
    }

    private void onNetworkStateChanged() {
        if (hasValidatedNetwork()) {
            onNetworkAvailable();
            return;
        }
        connectionAttemptId++;
        mqttConnected = false;
        mqttConnecting = false;
        setConnectionStatus(hasCachedTelemetry ? "Offline · showing last saved reading" : "Waiting for internet connection", false);
        disconnectClientThenRetry();
    }

    private void attemptMqttConnection() {
        if (client == null || mqttConnected || mqttConnecting || mqttDisconnecting) return;
        if (!hasValidatedNetwork()) {
            setConnectionStatus(hasCachedTelemetry ? "Offline · showing last saved reading" : "Waiting for internet connection", false);
            scheduleReconnect();
            return;
        }
        cancelScheduledReconnect();
        mqttConnecting = true;
        long attemptId = ++connectionAttemptId;
        setConnectionStatus(hasCachedTelemetry ? "Connecting · showing last saved reading" : "Connecting to gateway", false);
        client.connectWith()
                .simpleAuth()
                .username("SoilBuddyPrototype")
                .password("FarmWareTopSecretPassword12".getBytes(StandardCharsets.UTF_8))
                .applySimpleAuth()
                .send()
                .whenComplete((connAck, error) -> runOnUiThread(() -> {
                    if (attemptId != connectionAttemptId) return;
                    mqttConnecting = false;
                    if (error != null) {
                        mqttConnected = false;
                        setConnectionStatus(hasCachedTelemetry ? "Offline · showing last saved reading" : "Connection unavailable", false);
                        scheduleReconnect();
                    } else {
                        mqttConnected = true;
                        reconnectDelayMs = INITIAL_RETRY_DELAY_MS;
                        setConnectionStatus("Connected · waiting for sensor", true);
                        subscribeToTelemetry();
                    }
                }));
    }

    private void scheduleReconnect() {
        if (reconnectRunnable != null) return;
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(MAX_RETRY_DELAY_MS, reconnectDelayMs * 2);
        reconnectRunnable = () -> {
            reconnectRunnable = null;
            attemptMqttConnection();
        };
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private void cancelScheduledReconnect() {
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    private void disconnectClientThenRetry() {
        connectionAttemptId++;
        mqttConnected = false;
        mqttConnecting = false;
        if (client == null || mqttDisconnecting) {
            scheduleReconnect();
            return;
        }
        mqttDisconnecting = true;
        client.disconnect().whenComplete((ignored, error) -> mainHandler.post(() -> {
            mqttDisconnecting = false;
            if (hasValidatedNetwork()) attemptMqttConnection();
            else scheduleReconnect();
        }));
    }

    private void subscribeToTelemetry() {
        client.subscribeWith()
                .topicFilter(DATA_TOPIC)
                .callback(publish -> {
                    String topic = publish.getTopic().toString();
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
                    runOnUiThread(() -> renderPayload(topic, payload, true));
                })
                .send()
                .whenComplete((subAck, error) -> runOnUiThread(() -> {
                    if (error != null) {
                        setConnectionStatus("Subscription failed · reconnecting", false);
                        disconnectClientThenRetry();
                    }
                }));
    }

    private void renderPayload(String topic, String payload, boolean fromMqtt) {
        try {
            if (payload.trim().startsWith("{")) {
                renderJson(new JSONObject(payload), fromMqtt);
            } else {
                renderScalar(topic, payload.trim());
            }
            if (fromMqtt) {
                getSharedPreferences(CACHE_PREFS, MODE_PRIVATE).edit()
                        .putString("topic", topic)
                        .putString("payload", payload)
                        .apply();
                hasCachedTelemetry = true;
                setConnectionStatus("Live sensor data", true);
            } else {
                setConnectionStatus("Offline · showing last saved reading", false);
            }
        } catch (JSONException exception) {
            setConnectionStatus("Received unreadable data", false);
        }
    }

    private void renderJson(JSONObject packet, boolean persistHistory) {
        setMetric(packet, airTemperature, "°C", "bme_temp", "bmeTemp");
        setMetric(packet, humidity, "%", "bme_humid", "bmeHumid");
        setMetric(packet, airQuality, " IAQ", "bme_iaq", "bmeIaq");
        setMetric(packet, co2, " ppm", "bme_co2", "bmeCo2");
        setMetric(packet, vpd, " Pa", "bme_vpd", "bmeVpd");
        setMetric(packet, probeTemperature, "°C", "probe_temp", "probeTemperature");
        setMetric(packet, rawMoisture, "", "stemma_raw_moisture", "stemma_moisture");
        setMetric(packet, accuracy, "", "bme_acc", "bmeAcc");
        setNpkMetric(packet, nitrogen, nitrogenLevel, NITROGEN_THRESHOLDS);
        setNpkMetric(packet, phosphorus, phosphorusLevel, PHOSPHORUS_THRESHOLDS);
        setNpkMetric(packet, potassium, potassiumLevel, POTASSIUM_THRESHOLDS);

        String percent = find(packet, "stemma_moisture_percent", "moisture_percent", "moisturePercentage");
        if (percent != null) setMoisturePercent(percent);
        String timestamp = find(packet, "data_time", "timestamp", "time", "dataTime");
        Long timeMillis = null;
        if (timestamp != null) {
            homeReadingTime.setText("Updated " + timestamp);
            timeMillis = parseGatewayTimestamp(timestamp);
        }
        if (persistHistory && timeMillis != null) {
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
            case "npk-raw-nit": setNpkScalar(nitrogen, nitrogenLevel, NITROGEN_THRESHOLDS, value); break;
            case "npk-raw-phos": setNpkScalar(phosphorus, phosphorusLevel, PHOSPHORUS_THRESHOLDS, value); break;
            case "npk-raw-pot": setNpkScalar(potassium, potassiumLevel, POTASSIUM_THRESHOLDS, value); break;
            case "moisture-percent": setMoisturePercent(value); break;
            default: break;
        }
    }

    private void setNpkScalar(TextView rawView, TextView levelView, NpkThreshold thresholds, String value) {
        rawView.setText("Raw: " + value);
        try {
            double number = Double.parseDouble(value);
            String level = thresholds.categoryName(number);
            levelView.setText("Provisional: " + level);
        } catch (NumberFormatException ignored) {
            levelView.setText(thresholds.nutrient + " value unavailable for comparison");
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
                              NpkThreshold thresholds) {
        String rawValue = find(packet, thresholds.metricKey);
        if (rawValue == null) return;
        setNpkScalar(rawView, levelView, thresholds, rawValue);
        try {
            double value = Double.parseDouble(rawValue);
            String level = thresholds.categoryName(value);
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
        cancelScheduledReconnect();
        mainHandler.removeCallbacksAndMessages(null);
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // The callback may already have been unregistered by the system.
            }
        }
        if (client != null) client.disconnect();
        historyExecutor.shutdown();
        if (historyStore != null) historyStore.close();
        super.onDestroy();
    }
}
