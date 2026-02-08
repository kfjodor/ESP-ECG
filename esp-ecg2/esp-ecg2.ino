#include "test_data.h"

#define TEST 1

#define LO_MINUS 18  // io18
#define LO_PLUS 17   // io17
//#define OUTPUT 19    // io19
#define OUTPUT 35
#define SDN 16  // io16

void setup() {
  Serial.begin(115200);
#ifndef TEST
  pinMode(LO_MINUS, INPUT);  // Setup for leads off detection LO -
  pinMode(LO_PLUS, INPUT);   // Setup for leads off detection LO +
                             //pinMode(OUTPUT, INPUT);

  // Wake up from light sleep on GPIO12 when it goes LOW
  gpio_wakeup_enable(GPIO_NUM_18, GPIO_INTR_LOW_LEVEL);
  gpio_wakeup_enable(GPIO_NUM_17, GPIO_INTR_LOW_LEVEL);
  esp_sleep_enable_gpio_wakeup();
  Serial.println("Entering light sleep");
  digitalWrite(SDN, 0);
  esp_light_sleep_start();
#endif
}

int currentTest = 0;
int max_val = 0;
int last_heartbeat_time = 0;

void loop() {
#ifndef TEST
  //Serial.println("Enabling AD\n");
  digitalWrite(SDN, 1);

  int lo1 = digitalRead(LO_PLUS);
  int lo2 = digitalRead(LO_MINUS);

  if (lo1 == 1 && lo2 == 1) {
    //Serial.println("Light sleep again");
    digitalWrite(SDN, 0);
    esp_light_sleep_start();
  }

  if (lo1 == 0 && lo2 == 0) {
    int out = analogRead(OUTPUT);
    if (out > max_val) {
      max_val = out;
    }

    Serial.printf("%d\n", out);
  }
  /*
    if (out > 2400) {
      int hr = millis();
      int deltaMillis = hr - last_heartbeat_time;
      Serial.printf("Delta [%d]\n", deltaMillis);
      last_heartbeat_time = hr;
    }
  }
*/
#else

#endif
  Serial.printf("%d\n", testData[currentTest]);
  currentTest++;
  if (currentTest > 79) {
    currentTest = 0;
  }

  delay(10);
}