#!/usr/bin/bash 

stty -F /dev/ttyUSB0 115200 raw
cat /dev/ttyUSB0 | tee zai.log
