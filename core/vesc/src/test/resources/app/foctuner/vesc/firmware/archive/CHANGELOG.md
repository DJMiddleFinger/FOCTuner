### 7.01
#### TBD
* Major IMU refactor, IMU DRDY support. See https://github.com/vedderb/bldc/pull/917

### 7.00
#### 2026-05-15
* Configurable HFI reset ERPM.
* Removed D axis gain scaling parameters.
* Sin/Cos encoder filter delay compensation.
* Moved field weakening to fast loop for better stability.
* New AB encoder sensor mode for encoders without index pulse.
* Added MA782 encoder support. See https://github.com/vedderb/bldc/pull/886
* Added AMT22 encoder support.
* LispBM
	* Performance improvements and bug fixes
	* AS5047 encoder pin remapping support
	* Hall sensor pin remapping support
	* Many new extensions and configuration parameters
* VESC Remote coasting brake support.
* Use highest value of field weakening and MTPA current instead of summing both.
* Add FOC option to leave voltage headroom for Vq under full modulation.
* Added new fault codes that can be enabled or disabled:
	* Encoder slip
	* Overspeed
	* Underspeed
	* Absolute overspeed
* Added field weakening backoff gain parameter.
* SPI-support for LSM6DS3 IMU

### 6.06.6
#### 2025-11-01
* Persistent CAN ID and CAN Baud across firmware updates.
* Better current controller circle limiting.

### 6.06
#### 2025-08-18
* LispBM:
	* New core extansions, optimization and much more unit testing.
	* Many bug fixes in the reader.
	* Moved const memory into its own flash sector.
	* Added const-heap-erase extension.
	* Added mutex support.
	* Added can-ping extension.
	* LBM Image support
		* Super fast boot possible
		* Much easier to use const blocks
		* Rebuild image and const data when needed
	* Added conf-detect-lambda-enc.
	* Added many configuration parameters.
* New offset calibration modes and options.
* Automatic offset calibration support.
* Added HFI ambiguity resolution modes using id injection.
* Support for coordinated CAN baudrate update.
* Overmodulation support.
* Added PWM and PWM+ABI encoder modes.
* Detect encoder parameters during flux linkage detection.

### 6.05
#### 2024-08-19
* LispBM:
	* Support for var in progn.
	* Support for curly brackets instead of progn.
