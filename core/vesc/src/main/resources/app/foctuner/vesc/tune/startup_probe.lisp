; FOCTuner startup probe, protocol 2.
;
; Streamed by the app's Startup tuning tool and never stored. It runs sensorless start trials on this
; controller and reports what the firmware sees during them over COMM_CUSTOM_APP_DATA (send-data,
; event-data-rx). Parameter changes go through conf-set, which doesn't write flash; the app sets the
; old values back when it is done, and a reboot restores them in any case. The probe also keeps the values it
; found at the first hello: if the app goes quiet for ft-quiet seconds while no start or measurement runs (a lost
; link, a killed app), the probe sets them back itself, re-enables app output and stops answering, so test values
; can't stay in use until the next reboot. The app pings every second while it holds the probe.
;
; Needs firmware 6.05 or later (get-rpm-faster, get-iq-set, phase-observer, phase-motor). Loop state
; lives in globals named ft-...: on firmware where loopwhile is a macro it binds res, break, loop and
; brk around its body, which would shadow locals with those names.
;
; Every packet starts with 70 84 (FT), the protocol version and a type. Commands from the app:
;   1 hello [motor u8]            -> 129 [version u8, motor u8]
;   2 set [n u8, n x (param u8, value f32)] -> 130 params
;   3 get [n u8, n x param u8]    -> 130 [n u8, n x (param u8, ok u8, value f32)]
;   4 trial [id u16, current f32, target erpm f32, timeout ms u16, period ms u8, brake current f32,
;           rest erpm f32, rest ms u16, max fet temp f32, max motor temp f32, min input voltage f32]
;                                 -> 132 data [id u16, first u16, count u8, 8 x 26-byte samples]
;                                 -> 133 end [id u16, outcome u8, samples u16, reach ms u16, fault u8,
;                                             vin f32, fet temp f32, motor temp f32, est lambda f32, est res f32]
;                                 -> 137 released [id u16] once the motor is let go
;   5 stop                        (the running trial ends with outcome 3)
;   6 quit                        -> 135
;   7 measure resistance [current f32] -> 136 [ok u8, ohm f32]
;   8 ping                        (no reply; resets the quiet timer)
; 138 reports that the app went quiet and the probe set the saved values back and stopped.
; 134 [code u8] reports a command that can't run now (1: a trial or measurement is running).
;
; Sample: t ms u16, rpm i16, rpm-faster i16, iq*50 i16, id*50 i16, iq-set*50 i16, vd*100 i16,
; vq*100 i16, phase-observer*100 u16, phase-motor*100 u16, duty*10000 i16, est-lambda*1e5 u16,
; est-res*1e4 u16. rpm, iq, iq-set and duty follow m_invert_direction like the motor commands; the
; others are the firmware's internal values.
;
; Trial outcomes: 0 reached the target, 1 timed out, 2 fault, 3 stopped, 4 never came to rest,
; 5 temperature limit, 6 voltage limit, 7 script error.

(def ft-probe 2)
(def ft-params '(foc-observer-gain foc-openloop-rpm foc-openloop-rpm-low foc-sl-openloop-time-lock foc-sl-openloop-time-ramp foc-sl-openloop-time foc-observer-type foc-motor-r foc-motor-flux-linkage))
(def ft-nparams 9)
(def ft-quiet 5.0)
(def ft-saved nil)
(def ft-orig (bufcreate 45))
(def ft-seen (systime))
(def ft-j 0)
(def ft-alive t)
(def ft-busy nil)
(def ft-stop nil)
(def ft-motor 1)
(def ft-pkt (bufcreate 217))
(def ft-end (bufcreate 32))
(def ft-n 0)
(def ft-k 0)
(def ft-out 0)
(def ft-reach 0)
(def ft-fault 0)
(def ft-lam 0)
(def ft-res 0)
(def ft-t0 0)
(def ft-still 0)
(def ft-i 0)

(defun ft-abs (x) (if (< x 0) (- 0 x) x))
(defun ft-i16 (x) (if (> x 32767) 32767 (if (< x -32767) -32767 x)))
(defun ft-u16 (x) (if (> x 65535) 65535 (if (< x 0) 0 x)))
(defun ft-param (p) (ix ft-params p))

; The values at the first hello, 5 bytes a parameter: ok u8, value f32. ok stays 0 where conf-get fails.
(defun ft-save ()
  (progn
    (trap (select-motor ft-motor))
    (def ft-i 0)
    (loopwhile (< ft-i ft-nparams)
      (progn
        (bufset-u8 ft-orig (* ft-i 5) 0)
        (if (eq (car (trap (bufset-f32 ft-orig (+ (* ft-i 5) 1) (conf-get (ft-param ft-i))))) 'exit-ok) (bufset-u8 ft-orig (* ft-i 5) 1) nil)
        (def ft-i (+ ft-i 1))))
    (def ft-saved t)))

; Sets the saved values back; runs in the watchdog's thread, so it has its own counter.
(defun ft-restore ()
  (progn
    (trap (select-motor ft-motor))
    (def ft-j 0)
    (loopwhile (< ft-j ft-nparams)
      (progn
        (if (= (bufget-u8 ft-orig (* ft-j 5)) 1) (trap (conf-set (ft-param ft-j) (bufget-f32 ft-orig (+ (* ft-j 5) 1)))) nil)
        (def ft-j (+ ft-j 1))))))

(defun ft-head (buf type)
  (progn
    (bufset-u8 buf 0 70)
    (bufset-u8 buf 1 84)
    (bufset-u8 buf 2 ft-probe)
    (bufset-u8 buf 3 type)))

(defun ft-error (code)
  (let ((b (bufcreate 5)))
    (progn
      (ft-head b 134)
      (bufset-u8 b 4 code)
      (send-data b)
      (free b))))

(defun ft-hello (m)
  (progn
    (if (or (= m 1) (= m 2)) (def ft-motor m) nil)
    (trap (select-motor ft-motor))
    (if ft-saved nil (ft-save))
    (let ((b (bufcreate 6)))
      (progn
        (ft-head b 129)
        (bufset-u8 b 4 ft-probe)
        (bufset-u8 b 5 ft-motor)
        (send-data b)
        (free b)))))

; One parameter of the reply; ok stays 0 when conf-get fails or doesn't return a number.
(defun ft-report-one (b off p)
  (progn
    (bufset-u8 b off p)
    (bufset-u8 b (+ off 1) 0)
    (bufset-f32 b (+ off 2) 0)
    (if (eq (car (trap (bufset-f32 b (+ off 2) (conf-get (ft-param p))))) 'exit-ok) (bufset-u8 b (+ off 1) 1) nil)))

(defun ft-report (d start stride)
  (let ((n (bufget-u8 d 4)))
    (let ((b (bufcreate (+ 5 (* n 6)))))
      (progn
        (ft-head b 130)
        (bufset-u8 b 4 n)
        (def ft-i 0)
        (loopwhile (< ft-i n)
          (progn
            (ft-report-one b (+ 5 (* ft-i 6)) (bufget-u8 d (+ start (* ft-i stride))))
            (def ft-i (+ ft-i 1))))
        (send-data b)
        (free b)))))

(defun ft-set (d)
  (progn
    (trap (select-motor ft-motor))
    (def ft-i 0)
    (loopwhile (< ft-i (bufget-u8 d 4))
      (progn
        (trap (conf-set (ft-param (bufget-u8 d (+ 5 (* ft-i 5)))) (bufget-f32 d (+ 6 (* ft-i 5)))))
        (def ft-i (+ ft-i 1))))
    (ft-report d 5 5)))

(defun ft-get (d)
  (progn
    (trap (select-motor ft-motor))
    (ft-report d 5 1)))

(defun ft-sample (off)
  (progn
    (bufset-u16 ft-pkt off (ft-u16 (* 1000.0 (secs-since ft-t0))))
    (bufset-i16 ft-pkt (+ off 2) (ft-i16 (get-rpm)))
    (bufset-i16 ft-pkt (+ off 4) (ft-i16 (get-rpm-faster)))
    (bufset-i16 ft-pkt (+ off 6) (ft-i16 (* 50 (get-iq))))
    (bufset-i16 ft-pkt (+ off 8) (ft-i16 (* 50 (get-id))))
    (bufset-i16 ft-pkt (+ off 10) (ft-i16 (* 50 (get-iq-set))))
    (bufset-i16 ft-pkt (+ off 12) (ft-i16 (* 100 (get-vd))))
    (bufset-i16 ft-pkt (+ off 14) (ft-i16 (* 100 (get-vq))))
    (bufset-u16 ft-pkt (+ off 16) (ft-u16 (* 100 (phase-observer))))
    (bufset-u16 ft-pkt (+ off 18) (ft-u16 (* 100 (phase-motor))))
    (bufset-i16 ft-pkt (+ off 20) (ft-i16 (* 10000 (get-duty))))
    (bufset-u16 ft-pkt (+ off 22) (ft-u16 (* 100000 (get-est-lambda))))
    (bufset-u16 ft-pkt (+ off 24) (ft-u16 (* 10000 (get-est-res))))))

(defun ft-flush (id first count)
  (progn
    (ft-head ft-pkt 132)
    (bufset-u16 ft-pkt 4 id)
    (bufset-u16 ft-pkt 6 first)
    (bufset-u8 ft-pkt 8 count)
    (send-data ft-pkt)))

(defun ft-finish (id)
  (progn
    (ft-head ft-end 133)
    (bufset-u16 ft-end 4 id)
    (bufset-u8 ft-end 6 ft-out)
    (bufset-u16 ft-end 7 ft-n)
    (bufset-u16 ft-end 9 (ft-u16 ft-reach))
    (bufset-u8 ft-end 11 ft-fault)
    (bufset-f32 ft-end 12 (get-vin))
    (bufset-f32 ft-end 16 (get-temp-fet))
    (bufset-f32 ft-end 20 (get-temp-mot))
    (bufset-f32 ft-end 24 ft-lam)
    (bufset-f32 ft-end 28 ft-res)
    (send-data ft-end)))

(defun ft-released (id)
  (let ((b (bufcreate 6)))
    (progn
      (ft-head b 137)
      (bufset-u16 b 4 id)
      (send-data b)
      (free b))))

; Drives the motor with cur until it passes target ERPM (in the direction of cur) or something stops it,
; sampling every period ms.
(defun ft-drive (id cur target tmo period tfet tmot vmin)
  (progn
    (app-disable-output (+ tmo 5000))
    (def ft-t0 (systime))
    (loopwhile (< ft-out 0)
      (progn
        (set-current cur)
        (ft-sample (+ 9 (* ft-k 26)))
        (def ft-n (+ ft-n 1))
        (def ft-k (+ ft-k 1))
        (if (= ft-k 8) (progn (ft-flush id (- ft-n 8) 8) (def ft-k 0)) nil)
        (def ft-fault (get-fault))
        (cond
          (ft-stop (def ft-out 3))
          ((> ft-fault 0) (def ft-out 2))
          ((> (* (if (< cur 0) -1 1) (get-rpm)) target) (progn (def ft-reach (* 1000.0 (secs-since ft-t0))) (def ft-out 0)))
          ((> (* 1000.0 (secs-since ft-t0)) tmo) (def ft-out 1))
          ((> (get-temp-fet) tfet) (def ft-out 5))
          ((> (get-temp-mot) tmot) (def ft-out 5))
          ((< (get-vin) vmin) (def ft-out 6))
          (t nil))
        (if (< ft-out 0) (sleep (/ period 1000.0)) nil)))
    (if (> ft-k 0) (ft-flush id (- ft-n ft-k) ft-k) nil)
    (def ft-lam (get-est-lambda))
    (def ft-res (get-est-res))))

(defun ft-brake (brake)
  (progn
    (def ft-t0 (systime))
    (loopwhile (and (> (ft-abs (get-rpm)) 150) (< (secs-since ft-t0) 3.0) (not ft-stop) (= (get-fault) 0))
      (progn
        (set-brake brake)
        (sleep 0.01)))))

(defun ft-trial (id cur target tmo period brake rest-erpm rest-ms tfet tmot vmin)
  (progn
    (trap (select-motor ft-motor))
    (def ft-n 0)
    (def ft-k 0)
    (def ft-out -1)
    (def ft-reach 0)
    (def ft-fault 0)
    (def ft-lam 0)
    (def ft-res 0)
    ; The rotor has to be still for rest-ms first; give up after 4 s.
    (def ft-t0 (systime))
    (def ft-still (systime))
    (loopwhile (and (< (secs-since ft-t0) 4.0) (< (secs-since ft-still) (/ rest-ms 1000.0)) (not ft-stop))
      (progn
        (if (> (ft-abs (get-rpm)) rest-erpm) (def ft-still (systime)) nil)
        (sleep 0.01)))
    (if ft-stop (def ft-out 3) (if (< (secs-since ft-still) (/ rest-ms 1000.0)) (def ft-out 4) nil))
    (if (< ft-out 0) (ft-drive id cur target tmo period tfet tmot vmin) nil)
    (ft-finish id)
    (if (or (= ft-out 0) (= ft-out 1) (= ft-out 5) (= ft-out 6)) (ft-brake brake) nil)
    (set-current 0)
    (ft-released id)))

(defun ft-trial-thd (id cur target tmo period brake rest-erpm rest-ms tfet tmot vmin)
  (progn
    (if (eq (car (trap (ft-trial id cur target tmo period brake rest-erpm rest-ms tfet tmot vmin))) 'exit-error)
      (progn
        (set-current 0)
        (def ft-out 7)
        (trap (ft-finish id))
        (trap (ft-released id)))
      nil)
    (def ft-seen (systime))
    (def ft-busy nil)))

(defun ft-start-trial (d)
  (if ft-busy
    (ft-error 1)
    (progn
      (def ft-busy t)
      (def ft-stop nil)
      (spawn 512 ft-trial-thd (bufget-u16 d 4) (bufget-f32 d 6) (bufget-f32 d 10) (bufget-u16 d 14) (bufget-u8 d 16)
        (bufget-f32 d 17) (bufget-f32 d 21) (bufget-u16 d 25) (bufget-f32 d 27) (bufget-f32 d 31) (bufget-f32 d 35)))))

; The firmware's resistance measurement; ok stays 0 when it fails or returns no number.
(defun ft-res-thd (cur)
  (progn
    (trap (select-motor ft-motor))
    (let ((b (bufcreate 9)))
      (progn
        (ft-head b 136)
        (bufset-u8 b 4 0)
        (bufset-f32 b 5 0)
        (if (eq (car (trap (bufset-f32 b 5 (conf-measure-res cur)))) 'exit-ok) (bufset-u8 b 4 1) nil)
        (send-data b)
        (free b)))
    (def ft-seen (systime))
    (def ft-busy nil)))

(defun ft-start-res (d)
  (if ft-busy
    (ft-error 1)
    (progn
      (def ft-busy t)
      (spawn 512 ft-res-thd (bufget-f32 d 4)))))

(defun ft-quit ()
  (progn
    (def ft-stop t)
    (def ft-alive nil)
    (app-disable-output 0)
    (let ((b (bufcreate 4)))
      (progn
        (ft-head b 135)
        (send-data b)
        (free b)))))

(defun ft-handle (d)
  (if (and ft-alive (> (buflen d) 3) (= (bufget-u8 d 0) 70) (= (bufget-u8 d 1) 84) (= (bufget-u8 d 2) ft-probe))
    (let ((cmd (bufget-u8 d 3)))
      (progn
        (def ft-seen (systime))
        (cond
          ((= cmd 1) (ft-hello (bufget-u8 d 4)))
          ((= cmd 2) (ft-set d))
          ((= cmd 3) (ft-get d))
          ((= cmd 4) (ft-start-trial d))
          ((= cmd 5) (def ft-stop t))
          ((= cmd 6) (ft-quit))
          ((= cmd 7) (ft-start-res d))
          ((= cmd 8) nil)
          (t nil))))
    nil))

(defun ft-gave-up ()
  (let ((b (bufcreate 4)))
    (progn
      (ft-head b 138)
      (send-data b)
      (free b))))

; The dead man's switch: nothing from the app for ft-quiet seconds, nothing running, values saved.
(defun ft-watch ()
  (loopwhile ft-alive
    (progn
      (sleep 0.25)
      (if (and ft-alive ft-saved (not ft-busy) (> (secs-since ft-seen) ft-quiet))
        (progn
          (def ft-alive nil)
          (def ft-stop t)
          (trap (ft-restore))
          (app-disable-output 0)
          (trap (ft-gave-up)))
        nil))))

(defun ft-events ()
  (loopwhile ft-alive
    (recv
      ((event-data-rx . (? d)) (trap (ft-handle d)))
      (_ nil))))

(event-register-handler (spawn 512 ft-events))
(event-enable 'event-data-rx)
(spawn 256 ft-watch)
