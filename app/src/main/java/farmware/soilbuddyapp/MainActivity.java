package farmware.soilbuddyapp;

import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;

import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    private Mqtt3AsyncClient client;

    private String bme_acc_topic = "soil-buddy-prototype/bme-acc";
    private String bme_iaq_topic = "soil-buddy-prototype/bme-iaq";
    private String bme_co2_topic = "soil-buddy-prototype/bme-co2";
    private String bme_temp_topic = "soil-buddy-prototype/bme-temp";
    private String bme_humid_topic = "soil-buddy-prototype/bme-humid";
    private String bme_vpd_topic = "soil-buddy-prototype/bme-vpd";
    private String probe_temp_topic = "soil-buddy-prototype/probe-temp";
    private String stemma_moisture_topic = "soil-buddy-prototype/stemma-moisture";
    private String data_time_topic = "soil-buddy-prototype/data-time";
    private String next_big_cycle_topic = "soil-buddy-prototype/next-big-cycle";
    private String sampling_interval_topic = "soil-buddy-prototype/sampling-interval";
    private String stabilizing_samples_topic = "soil-buddy-prototype/stabilizing-samples";
    private String actual_samples_topic = "soil-buddy-prototype/actual-samples";

    private TextView bme_acc;
    private TextView bme_iaq;
    private TextView bme_co2;
    private TextView bme_temp;
    private TextView bme_humid;
    private TextView bme_vpd;
    private TextView probe_temp;
    private TextView stemma_moisture;
    private TextView data_time;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        bme_acc = findViewById(R.id.bme_acc_text);
        bme_iaq = findViewById(R.id.bme_iaq_text);
        bme_co2 = findViewById(R.id.bme_co2_text);
        bme_temp = findViewById(R.id.bme_temp_text);
        bme_humid = findViewById(R.id.bme_humid_text);
        bme_vpd = findViewById(R.id.bme_vpd_text);
        probe_temp = findViewById(R.id.probe_temp_text);
        stemma_moisture = findViewById(R.id.stemma_moisture_text);
        data_time = findViewById(R.id.data_time_text);

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
                .password("FarmWareTopSecretPassword12".getBytes())
                .applySimpleAuth()
                .send()
                .whenComplete((connAck, throwable) -> {
                    if (throwable != null) {
                        // handle failure
                    } else {
                        subscriptions();
                    }
                });
    }

    private void subscriptions() {
        client.subscribeWith()
                .topicFilter(bme_acc_topic)
                .callback(publish -> {
                    // Process the received message
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);

                    // Update UI on the main thread based on which subtopic triggered
                    runOnUiThread(() -> {
                        bme_acc.setText("BME680 Accuracy Score: " + payload);
                    });
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        // Handle failure to subscribe
                    } else {
                        // Handle successful subscription, e.g. logging or incrementing a metric
                    }
                });

        client.subscribeWith()
                .topicFilter(bme_iaq_topic)
                .callback(publish -> {
                    // Process the received message
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);

                    // Update UI on the main thread based on which subtopic triggered
                    runOnUiThread(() -> {
                        bme_iaq.setText("BME680 IAQ: " + payload);
                    });
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        // Handle failure to subscribe
                    } else {
                        // Handle successful subscription, e.g. logging or incrementing a metric
                    }
                });

        client.subscribeWith()
                .topicFilter(bme_co2_topic)
                .callback(publish -> {
                    // Process the received message
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);

                    // Update UI on the main thread based on which subtopic triggered
                    runOnUiThread(() -> {
                        bme_co2.setText("BME680 CO2: " + payload + " PPM");
                    });
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        // Handle failure to subscribe
                    } else {
                        // Handle successful subscription, e.g. logging or incrementing a metric
                    }
                });

        client.subscribeWith()
                .topicFilter(bme_temp_topic)
                .callback(publish -> {
                    // Process the received message
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);

                    // Update UI on the main thread based on which subtopic triggered
                    runOnUiThread(() -> {
                        bme_temp.setText("BME680 Temperature: " + payload + " C");
                    });
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        // Handle failure to subscribe
                    } else {
                        // Handle successful subscription, e.g. logging or incrementing a metric
                    }
                });

        client.subscribeWith()
                .topicFilter(bme_humid_topic)
                .callback(publish -> {
                    // Process the received message
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);

                    // Update UI on the main thread based on which subtopic triggered
                    runOnUiThread(() -> {
                        bme_humid.setText("BME680 Humidity: " + payload + " %");
                    });
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        // Handle failure to subscribe
                    } else {
                        // Handle successful subscription, e.g. logging or incrementing a metric
                    }
                });

        client.subscribeWith()
                .topicFilter(bme_vpd_topic)
                .callback(publish -> {
                    // Process the received message
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);

                    // Update UI on the main thread based on which subtopic triggered
                    runOnUiThread(() -> {
                        bme_vpd.setText("BME VPD: " + payload + " PA");
                    });
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        // Handle failure to subscribe
                    } else {
                        // Handle successful subscription, e.g. logging or incrementing a metric
                    }
                });

        client.subscribeWith()
                .topicFilter(probe_temp_topic)
                .callback(publish -> {
                    // Process the received message
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);

                    // Update UI on the main thread based on which subtopic triggered
                    runOnUiThread(() -> {
                        probe_temp.setText("DS18B20 Temperature: " + payload + " C");
                    });
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        // Handle failure to subscribe
                    } else {
                        // Handle successful subscription, e.g. logging or incrementing a metric
                    }
                });

        client.subscribeWith()
                .topicFilter(stemma_moisture_topic)
                .callback(publish -> {
                    // Process the received message
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);

                    // Update UI on the main thread based on which subtopic triggered
                    runOnUiThread(() -> {
                        stemma_moisture.setText("STEMMA Soil Capacitance: " + payload);
                    });
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        // Handle failure to subscribe
                    } else {
                        // Handle successful subscription, e.g. logging or incrementing a metric
                    }
                });

        client.subscribeWith()
                .topicFilter(data_time_topic)
                .callback(publish -> {
                    // Process the received message
                    String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);

                    // Update UI on the main thread based on which subtopic triggered
                    runOnUiThread(() -> {
                        data_time.setText("Measurement Time: " + payload);
                    });
                })
                .send()
                .whenComplete((subAck, throwable) -> {
                    if (throwable != null) {
                        // Handle failure to subscribe
                    } else {
                        // Handle successful subscription, e.g. logging or incrementing a metric
                    }
                });
    }
}