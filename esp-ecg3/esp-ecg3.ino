#include <NimBLEDevice.h>

#define TEST 1
#define AD8232_PIN 35
#define SAMPLING_FREQ 500
#define PACKET_SIZE 20
#define TIMER_INTERVAL_US (1000000 / SAMPLING_FREQ)

NimBLEServer* pServer;
NimBLECharacteristic* pCharacteristic;
uint16_t dataBuffer[PACKET_SIZE];
volatile int bufferIndex = 0;
volatile bool bufferFull = false;
hw_timer_t* timer = NULL;

#if TEST == 1
uint16_t testData[80] = { 2048, 2200, 2400, 2600, 2800, 3000, 3200, 3400, 3600, 3800, 4000, 3800, 3600, 3400, 3200, 3000, 2800, 2600, 2400, 2200, 2048, 1800, 1600, 1400, 1200, 1000, 800, 600, 400, 200, 100, 200, 400, 600, 800, 1000, 1200, 1400, 1600, 1800, 2048, 2100, 2200, 2300, 2500, 3500, 4095, 3500, 2500, 2048, 2048, 2000, 1950, 1900, 1850, 1800, 1850, 1900, 1950, 2048, 2048, 2100, 2150, 2200, 2250, 2300, 2250, 2200, 2150, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048 };
volatile int testIdx = 0;
#endif

void IRAM_ATTR onTimer() {
  if (!bufferFull) {
#if TEST == 1
    dataBuffer[bufferIndex++] = testData[testIdx++];
    if (testIdx >= 80) testIdx = 0;
#else
    dataBuffer[bufferIndex++] = (uint16_t)analogRead(AD8232_PIN);
#endif
    if (bufferIndex >= PACKET_SIZE) bufferFull = true;
  }
}

// Новый формат колбэков для NimBLE
class MyServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) {
    Serial.printf(">>> Подключено! Клиент: %s\n", connInfo.getAddress().toString().c_str());
  };
  void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) {
    Serial.println(">>> Отключено. Перезапуск рекламы...");
    NimBLEDevice::startAdvertising();
  }
};

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("Старт системы...");

  NimBLEDevice::init("ESP32_ECG_TEST");
  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  NimBLEDevice::setMTU(128);

  NimBLEService* pService = pServer->createService("DEAD");
  pCharacteristic = pService->createCharacteristic("BEEF", NIMBLE_PROPERTY::NOTIFY | NIMBLE_PROPERTY::READ);

  // В NimBLE дескриптор 2902 для Notify добавляется этой командой:
  pCharacteristic->createDescriptor("2902", NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);

  pService->start();


  NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->addServiceUUID("DEAD");
  // Включаем флаги обнаружения (обязательно для Android/iOS)
  pAdvertising->setAppearance(0xBEEF);  // 0x0340 - Heart Rate Sensor appearance
  // Вместо setScanResponse используем просто старт
  pAdvertising->start();

  // Таймер
  timer = timerBegin(1000000);
  timerAttachInterrupt(timer, &onTimer);
  timerAlarm(timer, TIMER_INTERVAL_US, true, 0);
}

void loop() {
  // Проверяем количество подключений напрямую у сервера
  if (pServer->getConnectedCount() > 0) {
    if (bufferFull) {
      pCharacteristic->setValue((uint8_t*)dataBuffer, PACKET_SIZE * sizeof(uint16_t));
      pCharacteristic->notify();

      bufferIndex = 0;
      bufferFull = false;
      Serial.print(".");
    }
  } else {
    static unsigned long lastMsg = 0;
    if (millis() - lastMsg > 3000) {
      Serial.println("Свободен. Жду подключения...");
      lastMsg = millis();
    }
  }
}
