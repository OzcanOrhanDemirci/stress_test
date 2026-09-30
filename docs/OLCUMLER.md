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
| Pil gerilimi | yalnız pil yayınıyla | şarjda ve doluyken yayın gelmedi; pilde ölçülecek |
| Şarj sayacı (`CHARGE_COUNTER`) | ? | şarjda ve doluyken değişmedi; pilde ölçülecek |
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
