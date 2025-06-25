#include <ESP8266WiFi.h>
#include <WiFiClient.h>
#include <ESP8266WebServer.h>
#include <ESP8266mDNS.h>

// Motor control pins
#define MOTOR_A_EN D0      // Left motor enable (PWM)
#define MOTOR_A_IN1 D2     // Left motor forward
#define MOTOR_A_IN2 D3     // Left motor backward
#define MOTOR_B_EN D1      // Right motor enable (PWM)
#define MOTOR_B_IN1 D4     // Right motor backward
#define MOTOR_B_IN2 D7     // Right motor forward

// Ultrasonic sensor pins
#define TRIG_PIN D5        // Trigger pin for HC-SR04
#define ECHO_PIN D6        // Echo pin for HC-SR04

// Wi-Fi credentials
const char* ssid     = "MAZ";          // Your Wi-Fi SSID
const char* password = "12345678";     // Your Wi-Fi password
const char* host     = "rosrobot";     // mDNS host name

ESP8266WebServer server(80);

// Obstacle detection settings
const int   OBSTACLE_DISTANCE       = 30;   // cm threshold
bool        obstacleDetected       = false;
unsigned long lastObstacleCheck    = 0;
const unsigned long OBSTACLE_CHECK_INTERVAL = 200;  // ms

void setup() {
  // --- Initialize pins ---
  pinMode(MOTOR_A_EN, OUTPUT);
  pinMode(MOTOR_A_IN1, OUTPUT);
  pinMode(MOTOR_A_IN2, OUTPUT);
  pinMode(MOTOR_B_EN, OUTPUT);
  pinMode(MOTOR_B_IN1, OUTPUT);
  pinMode(MOTOR_B_IN2, OUTPUT);

  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  digitalWrite(TRIG_PIN, LOW);

  // Ensure motors are stopped at startup
  stopMotors();

  // Start serial for debugging
  Serial.begin(115200);
  delay(10);

  // --- Connect to Wi-Fi ---
  Serial.println("\nConnecting to Wi-Fi...");
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWi-Fi connected!");
  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());

  // --- Setup mDNS responder ---
  if (MDNS.begin(host)) {
    Serial.println("mDNS responder started: http://" + String(host) + ".local");
  }

  // --- Define HTTP routes ---
  server.on("/", handleRoot);
  server.on("/command", handleCommand);
  server.onNotFound(handleNotFound);

  server.begin();
  Serial.println("HTTP server started");
}

void loop() {
  server.handleClient();
  MDNS.update();

  // Periodically check for obstacles
  if (millis() - lastObstacleCheck > OBSTACLE_CHECK_INTERVAL) {
    checkObstacle();
    lastObstacleCheck = millis();
  }
}

// Handle requests to "/"
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

// Handle "/command?text=..."
void handleCommand() {
  String cmd = server.arg("text");
  cmd.toLowerCase();
  Serial.println("Received cmd: " + cmd);

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

// Handle unmatched routes
void handleNotFound() {
  server.send(404, "text/plain", "Not found");
}

// Drive both motors forward
void moveForward() {
  digitalWrite(MOTOR_A_IN1, HIGH);
  digitalWrite(MOTOR_A_IN2, LOW);
  analogWrite(MOTOR_A_EN, 150); // adjust speed (0–255)

  digitalWrite(MOTOR_B_IN1, LOW);
  digitalWrite(MOTOR_B_IN2, HIGH);
  analogWrite(MOTOR_B_EN, 150);
}

// Drive both motors backward
void moveBackward() {
  digitalWrite(MOTOR_A_IN1, LOW);
  digitalWrite(MOTOR_A_IN2, HIGH);
  analogWrite(MOTOR_A_EN, 150);

  digitalWrite(MOTOR_B_IN1, HIGH);
  digitalWrite(MOTOR_B_IN2, LOW);
  analogWrite(MOTOR_B_EN, 150);
}

// Turn robot left in place
void turnLeft() {
  digitalWrite(MOTOR_A_IN1, LOW);
  digitalWrite(MOTOR_A_IN2, HIGH);
  analogWrite(MOTOR_A_EN, 150);

  digitalWrite(MOTOR_B_IN1, LOW);
  digitalWrite(MOTOR_B_IN2, HIGH);
  analogWrite(MOTOR_B_EN, 150);
}

// Turn robot right in place
void turnRight() {
  digitalWrite(MOTOR_A_IN1, HIGH);
  digitalWrite(MOTOR_A_IN2, LOW);
  analogWrite(MOTOR_A_EN, 150);

  digitalWrite(MOTOR_B_IN1, HIGH);
  digitalWrite(MOTOR_B_IN2, LOW);
  analogWrite(MOTOR_B_EN, 150);
}

// Stop all motors
void stopMotors() {
  analogWrite(MOTOR_A_EN, 0);
  analogWrite(MOTOR_B_EN, 0);
}

// Measure distance via ultrasonic sensor
float getDistance() {
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);

  long duration = pulseIn(ECHO_PIN, HIGH, 30000);
  if (duration == 0) return -1; // timeout

  // Speed of sound ~343 m/s -> 0.0343 cm/µs, divide by 2 for round-trip
  return (duration * 0.0343) / 2;
}

// Update obstacleDetected flag
void checkObstacle() {
  float dist = getDistance();
  if (dist > 0 && dist < OBSTACLE_DISTANCE) {
    if (!obstacleDetected) {
      Serial.println("Obstacle at " + String(dist) + " cm");
      stopMotors();
    }
    obstacleDetected = true;
  } else {
    obstacleDetected = false;
  }
}
