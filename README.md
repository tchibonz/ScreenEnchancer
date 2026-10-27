# Screen Enhancer (Android)

Telefondaki videolari (ve uygulama ici tarayicidaki web videolarini) oynatirken GPU'da iyilestirir.

## Ozellikler
- **Video oynatici**: Open video ile dosya secin. Kare basina FSRCNN yapay zeka yukseltmesi (2x, parlaklik kanali) + keskinlestirme/kontrast/doygunluk.
- **AI modu** (dugme): Auto / Off / Small / Full.
  - Auto: video cozunurluguna gore model secer (<=~540p full, <=~720p small, ustu klasik filtre),
    her ~120 karede GPU suresini olcer (kare suresinin %35'ini asarsa bir ust modeli kapatir),
    telefonun termal durumunu izler (isinirsa small'a, cok isinirsa klasik filtreye duser, soguyunca geri cikar).
  - Durum satiri panelde gorunur (model, GPU ms, termal seviye).
- **Klasik yol** (AI kapaliyken, video 720p ustundeyken veya isinma/GPU sinirinda): kenar uyumlu yukseltme
  (FSR1 mantiginda, kendi yazdigimiz: 4x4 ornek, yapi tensorunden kenar yonu, kenar boyunca uzatilmis
  Catmull-Rom cekirdegi, ringing'e karsi sinirlama) + kontrast sinirli keskinlestirme.
  Olcum (kucultulup buyutulen test fotograflari): bicubic 31.55 dB / SSIM 0.895, eski filtre 31.52 / 0.9025,
  yeni 31.74 / 0.9036. Yani eskisinden daha iyi ama fark kucuk; en belirgin kazanc kenarlardaki basamaklanmanin
  azalmasi.
- **Browser** (Chrome'daki gibi film/dizi izleme icin): uygulama ici tarayici. Her sayfaya ve her iframe'e
  belge baslangicinda bir script (assets/ns_enhancer.js) enjekte edilir; sayfadaki <video> ogesi ayni GPU
  hattindan (FSRCNN AI veya kenar uyumlu yukseltici + keskinlestirici) gecirilip ustune canvas olarak cizilir.
  Ayni otomatik kalite/termal kontrolu burada da var. Enhance panelinde Enhance On/Off, AI modu, durum satiri
  ve kaydiricilar bulunur.
  Sinirlar: DRM'li (Netflix vb.) ve CORS izni olmayan baska-site videolari islenemez (video oldugu gibi kalir,
  durum satirinda yazar). Tam ekranda oynatici kabini (container) tam ekran yapilir; bazi oynaticilarda
  canvas gorunmeyebilir.

- **Yan panel**: ekranin sag kenarindaki ince cubugu sola cek (ya da dokun). 2K Keskinlestirme anahtari, AI modu,
  Keskinlik / Renk Doygunlugu / Kontrast yuzde kaydiricilari (oynaticida Once/Sonra). Hem oynatici hem Browser'da.

## Notlar
- Model: Saafke/FSRCNN_Tensorflow (Apache-2.0), bkz. third_party/FSRCNN. Anime'ye ozel degil, genel gorseller.
- Gain mutaldir (test fotograflarinda bicubic'e gore ~+0.3 dB small, ~+0.7 dB full). DLSS gibi degil.
- Shader'lar WebGL2'de dogrulandi (tools/test_webgl.py): GPU ciktisi numpy referansiyla ayni (ic bolgede ort. fark ~0.0002).
- Tarayici scripti headless Chromium'da gercek videoyla test edildi (tools/test_page.py: duz sayfa, iframe, baska-site video).
- Android uygulamasi cihazda test EDILMEDI. GLES 3.0 + EXT_color_buffer_float gerekir.
- Netflix/Disney+ vb. DRM'li uygulamalari ve baska uygulamalarin icini iyilestiremez.

## APK
GitHub Actions (.github/workflows/build.yml) ile derlenir; Actions > Build APK > Artifacts.
