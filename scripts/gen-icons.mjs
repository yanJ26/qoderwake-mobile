import { readFile } from 'node:fs/promises';
import path from 'node:path';
import sharp from 'sharp';

// qoderwake 自带 SVG 图标（拉自 qoderwake-cn:19830/qoderwake-icon-cn.svg，
// 1024x1024 viewBox，logo 在白底上居中）
const SRC_SVG = path.resolve('www/assets/qoderwake-icon.svg');
const RES_DIR = path.resolve('android/app/src/main/res');

const DENSITIES = {
  'mipmap-mdpi': 48,
  'mipmap-hdpi': 72,
  'mipmap-xhdpi': 96,
  'mipmap-xxhdpi': 144,
  'mipmap-xxxhdpi': 192,
};

const svg = await readFile(SRC_SVG, 'utf8');

async function renderLogo(px) {
  return sharp(Buffer.from(svg), { density: Math.max(72, Math.ceil((px / 50) * 72)) })
    .resize(px, px, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toBuffer();
}

for (const [dir, size] of Object.entries(DENSITIES)) {
  const outDir = path.join(RES_DIR, dir);

  // ic_launcher.png：白底方形 + 彩色 logo（占 78%，居中）
  const logo = await renderLogo(Math.round(size * 0.78));
  await sharp({
    create: { width: size, height: size, channels: 4, background: { r: 255, g: 255, b: 255, alpha: 1 } },
  })
    .composite([{ input: logo, gravity: 'center' }])
    .png()
    .toFile(path.join(outDir, 'ic_launcher.png'));

  // ic_launcher_round.png：白色圆形底 + 彩色 logo（占 62%，居中）
  const circleSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}"><circle cx="${size / 2}" cy="${size / 2}" r="${size / 2}" fill="#ffffff"/></svg>`;
  const roundLogo = await renderLogo(Math.round(size * 0.62));
  await sharp(Buffer.from(circleSvg))
    .composite([{ input: roundLogo, gravity: 'center' }])
    .png()
    .toFile(path.join(outDir, 'ic_launcher_round.png'));

  // ic_launcher_foreground.png：自适应图标前景，108dp 画布（2.25x），
  // logo 占画布 55%（安全区 66/108 之内），透明底
  const fgSize = Math.round(size * 2.25);
  const fgLogo = await renderLogo(Math.round(fgSize * 0.55));
  await sharp({
    create: { width: fgSize, height: fgSize, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
  })
    .composite([{ input: fgLogo, gravity: 'center' }])
    .png()
    .toFile(path.join(outDir, 'ic_launcher_foreground.png'));

  console.log(`${dir}: ${size}px 完成`);
}

console.log('全部图标已生成');
