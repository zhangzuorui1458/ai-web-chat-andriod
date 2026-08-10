const { chromium } = require('playwright');

const sizes = [1024, 512, 192];

(async () => {
  const browser = await chromium.launch({ headless: true });
  for (const size of sizes) {
    const page = await browser.newPage({ viewport: { width: size, height: size } });
    await page.goto('file:///C:/Users/lenovo/Pictures/logo/logo.svg');
    await page.screenshot({ path: `C:/Users/lenovo/Pictures/logo/logo-${size}.png` });
    await page.close();
    console.log(`logo-${size}.png done`);
  }
  await browser.close();
})().catch(e => { console.error(e.message); process.exit(1); });
