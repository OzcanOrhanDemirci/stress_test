# Ölçümler

Her fazın telefonda ölçülen sonuçları. Burada yazmayan bir sayı ölçülmemiştir.
Cihaz: Honor 400 (DNY-NX9), seri `AKSC025610001388`, Android 16.

## Faz 0 · Ölçüm aracı

### Uygulama neyi okuyabiliyor (2026-10-01, release derleme, tanılama ekranı)

| Veri | Uygulama | Not |
|---|---|---|
| Çekirdek frekansı, 3 küme (`policy0/4/7`) | ✅ | |
| Sıcaklık: A715 (8/8 bölge), A510 (4/4), GPU (2/2), DDR (1/1), NPU (3/3), modem (4/4), kamera (1/1) | ✅ | Planın en büyük riski yoktu: hepsi açık |
| GPU meşgul sayacı (`kgsl-3d0/gpubusy`) | ✅ | GPU boştayken `0 0` okunur, oran yok |
| Pil `CURRENT_NOW` | ✅ | **1 Hz'de güncelleniyor** (10 Hz örnekte medyan değişim aralığı 1,000 sn) |
| Pil gerilimi | yalnız pil yayınıyla | pilde **~30 sn'de bir** güncelleniyor (medyan 30,7 sn) |
| Şarj sayacı (`CHARGE_COUNTER`) | ✅ ama **mAh** | Android µAh ister, Honor mAh veriyor (5679 @ %97 → tam ≈ 5,86 Ah); ~30 sn'de bir güncelleniyor |
| Termal pay (`getThermalHeadroom`) | ✅ | boşta 0,63 |

### Qualcomm `core_ctl` boştaki büyük çekirdekleri duraklatıyor

Boştayken ölçüldü (`/sys/devices/system/cpu/cpu4/core_ctl/global_state`): **CPU 5 ve CPU 7 duraklatılmış**.
A715 kümesinde `min_cpus=2`, prime'da `min_cpus=0`. Duraklatılmış bir çekirdeğe `sched_setaffinity` başarısız oluyor.
Cihaz testi bunu yakaladı (C9, CPU 7'ye sabitlenemedi). **Önlem:** yakıcı iş parçacığı sabitlenene kadar her paketten sonra
yeniden deniyor. Yük talebi `core_ctl`'i çekirdeği açmaya zorluyor. Test: `CpuEngineDeviceTest`, sekiz iş parçacığı 3 sn içinde
sabitlendi.

### İlk uçtan uca koşu (kablo takılıyken, güç geçersiz)

`fp32_gemm` her çekirdekte, 20 sn:

| Küme | İş | Çekirdek başına | Döngü başına | Tepeye oran |
|---|---|---|---|---|
| A510 ×4 @1,80 GHz | 46,0 GFLOPS | 11,5 | 6,4 FLOP | tepe değeri henüz bilinmiyor |
| A715 ×3 @2,40 GHz | 111,2 GFLOPS | 37,1 | 15,4 FLOP | %96 (2×128 bit FMA = 16 FLOP/döngü) |
| A715 prime @2,63 GHz | 38,5 GFLOPS | 38,5 | 14,6 FLOP | %91 |

Frekanslar 20 sn boyunca tavanda kaldı. Hesap hatası 0. 20 sn sonunda en sıcak bölge A510 (64,9 °C).

### Pilde güç ölçümü (2026-10-01 gecesi, oturum `20261001-010903`)

- `CURRENT_NOW`: **mA**, deşarjda **eksi**, **1 Hz** güncelleniyor. Uygulama birimi ve işareti boşta evresinden kendisi çıkarıyor (µA/mA ayrımı büyüklükten).
- Gerilim yalnız pil yayınıyla ~30 sn'de bir geliyor: yüke girişin ilk ~30 sn'sinde güç, boştaki (daha yüksek) gerilimle hesaplanıyor.
  Boşta 4,395 V, yükte 4,302 V → ilk 30 sn en çok ~%2 fazla. Bütün adaylara aynı biçimde etki ediyor, sıralamayı değiştirmez.
- Şarj sayacıyla çapraz kontrol bu oturumda **yapılamadı**: sayaç mAh ve 30 sn adımlı; 70 sn'lik pencerede 2-3 adım var. Kontrol için
  10 dk'lık kararlı bir yük gerekiyor (yapılacak). Sayaç birimi düzeltildi (mAh → µAh, `Power.normalizeChargeCounter`).

## Faz 1 · CPU aday taraması

Oturum `20261001-010903`: 11 yük × 2 tekrar, karışık sıra, her koşu 10 sn boşta + 60 sn yük, başlangıçta CPU ≤ 42 °C,
**performans modu açık** (`sys.aps.power_mode=3`), ekran parlaklığı 0,2, pil %100 → %96. Hesap hatası 0, yanlış çekirdek %0.

| Sıra | Yük | İlk 30 sn | Tüm 60 sn | Boşta üstü | Tekrar farkı | Son 30 sn frekans (A510/A715/prime) | En sıcak A715 |
|---|---|---|---|---|---|---|---|
| 1 | **C8 fp32_l2** (FP32 FMA, 256 KiB tampondan akış) | **6,39 W** | 6,31 W | 5,68 W | ±0,17 | 1472/2165/2270 MHz (kısıldı) | 85,8 °C |
| 2 | C5 i8_mmla | 5,27 W | 5,41 W | 4,75 W | ±0,05 | tavan | 78,8 °C |
| 3 | C3 fp64_gemm | 5,08 W | 5,23 W | 4,43 W | ±0,09 | tavan | 77,6 °C |
| 4 | C7 mixed | 4,95 W | 5,11 W | 4,46 W | ±0,14 | tavan | 76,2 °C |
| 5 | C4 bf16_mmla | 4,80 W | 4,89 W | 4,21 W | ±0,01 | tavan | 76,2 °C |
| 6 | C9 fp32_dram | 4,76 W | 4,90 W | 4,16 W | ±0,08 | tavan | 65,5 °C |
| 7 | C2 fp32_gemm | 4,54 W | 4,63 W | 3,93 W | ±0,01 | tavan | 71,2 °C |
| 8 | C10 memcopy | 4,13 W | 4,24 W | 3,53 W | ±0,08 | tavan | 60,7 °C |
| 9 | C6 i8_dot | 3,95 W | 4,05 W | 3,38 W | ±0,02 | tavan | 68,6 °C |
| 10 | C1 fp32_reg | 3,93 W | 3,98 W | 3,37 W | ±0,14 | tavan | 64,9 °C |
| 11 | **K0 kuru %100** | **2,46 W** | 2,39 W | 1,59 W | ±0,32 | tavan | 53,5 °C |

**Okuma:**
- **Prime95 / Cinebench farkı ölçüldü:** aynı "%100" altında kuru döngü 2,46 W, kazanan 6,39 W: **2,6 kat** (boşta üstü 3,6 kat).
- Kazanan C8, FMA birimleriyle birlikte L1'i aşan veriyi (L2) taşıyan yük. En yakın rakibinden 1,1 W önde, 60 sn içinde kısılmaya
  giren tek yük. Tavanı ısı belirledi: A715 85,8 °C.
- Yalnız yazmaçta çalışan FMA (C1) FMA birimlerini doldurduğu hâlde 3,93 W'ta kaldı. Watt'ı veri hareketi getiriyor.
- İş hızları kâğıt üstü tepeye yakın: A715'te FP32 %96, FP64 2×128 bit ile tam doluluk, INT8 SMMLA çekirdek başına ~122 işlem/döngü (~%95).
  A510'da BFMMLA yavaş (4 çekirdek 42 GFLOPS, INT8 SMMLA'nın 1/8'i).

**Sonraki CPU turu:** C8'in tampon boyu (128 KiB · 512 KiB · 1 MiB · 2 MiB) ve küme başına karışımlar (ör. A510'da i8_mmla, A715'te C8).

## Faz 2 · GPU motoru, ilk cihaz ölçümleri (güçsüz)

`GpuEngineDeviceTest`, 2026-10-01 01:51, kablo takılı, ImageReader yüzeyi 632×1368, sahne %50 (316×684), hedef kare 40 ms.
Kare zamanı iki zaman damgasıyla bölündü: yakıcı + görünen geçiş (sahne).

| Yakıcı | Kare | Yakıcı | Sahne | Gönderim/kare | Gönderim başı | Hız |
|---|---|---|---|---|---|---|
| G1 FP32 | 39,3 ms | 15,7 ms | 23,7 ms | 15 | 1,05 ms | ~1,0 TFLOPS (yakıcı süresine göre) |
| G2 FP16 | 38,5 ms | 15,0 ms | 23,6 ms | 3 | ~5 ms | ~0,2 TFLOPS: FP32'den ~5 kat yavaş (beklenmedik) |
| G3 doku | 45,8 ms | 17,2 ms | 23,7 ms | 2 | ~8,6 ms | her örnek farklı önbellek satırı: belleğe bağlı |
| G4 bant genişliği | 42,1 ms | 16,3 ms | 23,8 ms | 4 | ~4 ms | ~16 GB/s okuma+yazma |
| G5 harmanlama | 40,6 ms | 15,2 ms | 23,7 ms | 69 | 0,22 ms | ~15,7 Gpiksel/s |

- Hesap hatası 0, doğrulanan her gönderim ilk gönderimle birebir aynı.
- **Sahne pahalı:** 316×684'te 23,7 ms. Telefonun gerçek ekranında %50 ölçekte (632×1368) GPU modunda **14 fps** görüldü, GPU %100 meşgul.
  Hangi ölçeğin ve hangi yakıcının en çok watt çektiği gece oturumunda ölçülüyor.
- İki hata bulundu ve düzeltildi: G3 tek gönderimi ~100 ms'ydi (iterasyon 64 → 8); ayar döngüsü kare başına tek gönderimde
  takılıyordu (tek zaman damgasıyla sabit sahne maliyeti ayrılamıyordu → üç damga, `(hedef − sahne) / gönderim başı`).
