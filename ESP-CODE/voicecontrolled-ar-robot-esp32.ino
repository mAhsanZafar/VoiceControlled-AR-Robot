#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>

// Motor control pins (change to suit your wiring!)
#define MOTOR_A_EN_PIN   18  // PWM pin for left motor speed
#define MOTOR_A_IN1_PIN  19  // left motor forward
#define MOTOR_A_IN2_PIN  21  // left motor backward
#define MOTOR_B_EN_PIN   22  // PWM pin for right motor speed
#define MOTOR_B_IN1_PIN  23  // right motor backward
#define MOTOR_B_IN2_PIN   5  // right motor forward

// PWM channels & settings
const int MOTOR_A_CHANNEL = 0;
const int MOTOR_B_CHANNEL = 1;
const int  PWM_FREQ       = 5000;  // 5 kHz PWM
const int  PWM_RES        = 8;     // 8-bit resolution (0–255)

// Ultrasonic sensor pins
#define TRIG_PIN 17   // Trigger on HC-SR04
#define ECHO_PIN 16   // Echo on HC-SR04

// Wi-Fi credentials
const char* ssid     = "MAZ";
const char* password = "12345678";
const char* host     = "rosrobot";

WebServer server(80);

// Obstacle detection
const int    OBSTACLE_DISTANCE       = 30;  // cm threshold
bool         obstacleDetected       = false;
unsigned long lastObstacleCheck     = 0;
const unsigned long OBSTACLE_CHECK_INTERVAL = 200; // ms

void setup() {
  Serial.begin(115200);
  delay(10);

  // --- Configure motor pins & PWM ---
  pinMode(MOTOR_A_IN1_PIN, OUTPUT);
  pinMode(MOTOR_A_IN2_PIN, OUTPUT);
  pinMode(MOTOR_B_IN1_PIN, OUTPUT);
  pinMode(MOTOR_B_IN2_PIN, OUTPUT);
  // Setup PWM channels
  ledcSetup(MOTOR_A_CHANNEL, PWM_FREQ, PWM_RES);
  ledcAttachPin(MOTOR_A_EN_PIN, MOTOR_A_CHANNEL);
  ledcSetup(MOTOR_B_CHANNEL, PWM_FREQ, PWM_RES);
  ledcAttachPin(MOTOR_B_EN_PIN, MOTOR_B_CHANNEL);

  // Stop motors at startup
  stopMotors();

  // --- Ultrasonic sensor setup ---
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  digitalWrite(TRIG_PIN, LOW);

  // --- Connect to Wi-Fi ---
  Serial.println("\nConnecting to Wi-Fi...");
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWi-Fi connected, IP = " + WiFi.localIP().toString());

  // --- mDNS responder ---
  if (MDNS.begin(host)) {
    Serial.println("mDNS responder started: http://" + String(host) + ".local");
  }

  // --- HTTP routes ---
  server.on("/",        handleRoot);
  server.on("/command", handleCommand);
  server.onNotFound(    handleNotFound);
  server.begin();
  Serial.println("HTTP server started");
}

void loop() {
  server.handleClient();
  MDNS.update();

  // Periodic obstacle check
  if (millis() - lastObstacleCheck > OBSTACLE_CHECK_INTERVAL) {
    checkObstacle();
    lastObstacleCheck = millis();
  }
}

void handleRoot() {
  String msg = "ROS Robot Companion\n\n";
  msg += "Commands:\n";
  msg += "  /command?text=move_forward\n";
  msg += "  /command?text=move_backward\n";
  msg += "  /command?text=turn_left\n";
  msg += "  /command?text=turn_right\n";
  msg += "  /command?text=stop\n\n";
  msg += "Distance: " + String(getDistance()) + " cm\n";
  msg += "Obstacle: " + String(obstacleDetected ? "YES" : "NO");
  server.send(200, "text/plain", msg);
}

void handleCommand() {
  String cmd = server.arg("text");
  cmd.toLowerCase();
  Serial.println("Cmd: " + cmd);

  if (cmd == "hello") {
    server.send(200, "text/plain", "hello");
    return;
  }
  if (cmd == "move_forward") {
    if (!obstacleDetected) {
      moveForward();
      server.send(200, "text/plain", "Moving forward");
    } else {
      stopMotors();
      server.send(200, "text/plain", "Obstacle detected – cannot move forward");
    }
  }
  else if (cmd == "move_backward") {
    moveBackward();
    server.send(200, "text/plain", "Moving backward");
  }
  else if (cmd == "turn_left") {
    turnLeft();
    server.send(200, "text/plain", "Turning left");
  }
  else if (cmd == "turn_right") {
    turnRight();
    server.send(200, "text/plain", "Turning right");
  }
  else if (cmd == "stop") {
    stopMotors();
    server.send(200, "text/plain", "Stopped");
  }
  else {
    server.send(400, "text/plain", "Unknown command: " + cmd);
  }
}

void handleNotFound() {
  server.send(404, "text/plain", "Not found");
}

// --- Motor control helpers ---

void moveForward() {
  digitalWrite(MOTOR_A_IN1_PIN, HIGH);
  digitalWrite(MOTOR_A_IN2_PIN, LOW);
  ledcWrite(MOTOR_A_CHANNEL, 150);  // adjust speed 0–255

  digitalWrite(MOTOR_B_IN1_PIN, LOW);
  digitalWrite(MOTOR_B_IN2_PIN, HIGH);
  ledcWrite(MOTOR_B_CHANNEL, 150);
}

void moveBackward() {
  digitalWrite(MOTOR_A_IN1_PIN, LOW);
  digitalWrite(MOTOR_A_IN2_PIN, HIGH);
  ledcWrite(MOTOR_A_CHANNEL, 150);

  digitalWrite(MOTOR_B_IN1_PIN, HIGH);
  digitalWrite(MOTOR_B_IN2_PIN, LOW);
  ledcWrite(MOTOR_B_CHANNEL, 150);
}

void turnLeft() {
  digitalWrite(MOTOR_A_IN1_PIN, LOW);
  digitalWrite(MOTOR_A_IN2_PIN, HIGH);
  ledcWrite(MOTOR_A_CHANNEL, 150);

  digitalWrite(MOTOR_B_IN1_PIN, LOW);
  digitalWrite(MOTOR_B_IN2_PIN, HIGH);
  ledcWrite(MOTOR_B_CHANNEL, 150);
}

void turnRight() {
  digitalWrite(MOTOR_A_IN1_PIN, HIGH);
  digitalWrite(MOTOR_A_IN2_PIN, LOW);
  ledcWrite(MOTOR_A_CHANNEL, 150);

  digitalWrite(MOTOR_B_IN1_PIN, HIGH);
  digitalWrite(MOTOR_B_IN2_PIN, LOW);
  ledcWrite(MOTOR_B_CHANNEL, 150);
}

void stopMotors() {
  ledcWrite(MOTOR_A_CHANNEL, 0);
  ledcWrite(MOTOR_B_CHANNEL, 0);
}

// --- Ultrasonic functions ---

float getDistance() {
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);

  long duration = pulseIn(ECHO_PIN, HIGH, 30000);
  if (duration == 0) return -1;  // timeout
  return (duration * 0.0343) / 2; // cm
}

void checkObstacle() {
  float d = getDistance();
  if (d > 0 && d < OBSTACLE_DISTANCE) {
    if (!obstacleDetected) {
      Serial.println("Obstacle at " + String(d) + " cm");
      stopMotors();
    }
    obstacleDetected = true;
  } else {
    obstacleDetected = false;
  }
}
