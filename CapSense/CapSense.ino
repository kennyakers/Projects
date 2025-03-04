
#include <CapacitiveSensor.h>

CapacitiveSensor   cs_4_2 = CapacitiveSensor(4, 2);       // 10M resistor between pins 4 & 2, pin 2 is sensor pin, add a wire and or foil if desired

void setup()
{
  cs_4_2.set_CS_AutocaL_Millis(0xFFFFFFFF);     // turn off autocalibrate on channel 1 - just as an example
  Serial.begin(9600);

  pinMode(13, OUTPUT);
}

void loop()
{
  long total1 =  cs_4_2.capacitiveSensor(30);
  int ledVal = constrain(total1, 0, 255);

  if (ledVal > 45) {
    analogWrite(13, ledVal);
  }
  Serial.println(ledVal);                  // print sensor output 1

  delay(10);                             // arbitrary delay to limit data to serial port
}
