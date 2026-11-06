# Biladerler

Yapımcı: **@amdemnxtr**

Hesap makinesi görünümlü bir dış kapının (PIN) arkasında, Android İş Profili ile yalıtılmış bir çalışma alanı açar.

- Ana ekranda tek ikon: **Biladerler**. Açınca çalışan bir hesap makinesi görünür.
- Sağ üstteki ⚙ simgesine **7 saniye** basılı tut → PIN ekranı. İlk açılışta PIN'i sen belirlersin.
- PIN doğrulanınca yalıtılmış alan açılır. 2 dakika arka planda kalırsan tekrar PIN ister.
- Vault ekranlarında ekran görüntüsü ve son uygulamalar önizlemesi kapalıdır.

## VPN (her zaman açık, kilit modlu)
1. Açık kaynaklı bir VPN uygulamasını (WireGuard, Proton VPN vb.) **iş profilinin içine** kur ve bir kez yapılandır.
2. Ayarlar → **VPN** bölümünden o uygulamayı seç, "VPN olmadan bağlantıyı engelle" açık kalsın.
3. Artık iş profilindeki tüm trafik VPN üzerinden gider; VPN düşerse internet kesilir. Ana telefon etkilenmez.

Not: Sabit ABD IP'si için ücretsiz ve kartsız hazır servis yok; sabit IP kendi VPN sunucunla mümkündür.

## Derleme (bilgisayarsız)
Projeyi GitHub'da bir repoya yükle → **Actions → Android APK Build → Run workflow** → bitince `debug-apk` çıktısını indir.

## Lisans
GPL-3.0. Bu proje, PeterCxy tarafından yazılan açık kaynaklı **Shelter** projesinin değiştirilmiş bir sürümüdür.
GPL-3.0 gereği `LICENSE` ve `NOTICE.md` dosyaları korunmalıdır.
