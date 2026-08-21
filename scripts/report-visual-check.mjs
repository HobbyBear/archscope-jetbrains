#!/usr/bin/env node
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const [htmlArgument, outputArgument] = process.argv.slice(2);
if (!htmlArgument || !outputArgument) {
  console.error('Usage: node scripts/report-visual-check.mjs <report.html> <output-directory>');
  process.exit(2);
}

const htmlPath = path.resolve(htmlArgument);
const outputDirectory = path.resolve(outputArgument);
const playwrightRoots = [
  process.env.ARCHSCOPE_PLAYWRIGHT_ROOT,
  path.join(process.cwd(), 'node_modules'),
  path.join(os.tmpdir(), 'archscope-playwright', 'node_modules'),
].filter(Boolean);
const playwrightRoot = playwrightRoots.find(root => fs.existsSync(path.join(root, 'playwright')));
if (!playwrightRoot) {
  console.error('Playwright is unavailable. Set ARCHSCOPE_PLAYWRIGHT_ROOT to a node_modules directory containing playwright.');
  process.exit(2);
}

const require = createRequire(import.meta.url);
const { chromium } = require(path.join(playwrightRoot, 'playwright'));
const source = fs.readFileSync(htmlPath);
const sourceSha256 = crypto.createHash('sha256').update(source).digest('hex');
fs.mkdirSync(outputDirectory, { recursive: true });
const browser = await chromium.launch({ headless: true });
const viewports = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'laptop', width: 1024, height: 768 },
  { name: 'mobile', width: 390, height: 844 },
];
const results = [];

try {
  for (const viewport of viewports) {
    for (const theme of ['light', 'dark']) {
      const page = await browser.newPage({ viewport });
      await page.goto(pathToFileURL(htmlPath).href, { waitUntil: 'load' });
      await page.evaluate(value => { document.documentElement.dataset.theme = value; }, theme);
      await page.evaluate(() => { window.archscopeOpenSource = payload => { window.__capturedSourcePayload = payload; }; });
      await page.waitForTimeout(100);
      const metrics = await page.evaluate(() => {
        const rect = element => {
          const value = element.getBoundingClientRect();
          return { left: value.left, top: value.top, right: value.right, bottom: value.bottom, width: value.width, height: value.height };
        };
        const overlaps = (left, right) => left.left < right.right - 1 && left.right > right.left + 1
          && left.top < right.bottom - 1 && left.bottom > right.top + 1;
        const nodeRects = [...document.querySelectorAll('.flow-diagram .node,.flow-diagram .branch-node')]
          .map(element => ({ id: element.getAttribute('data-flow') || '', ...rect(element) }));
        const nodeOverlaps = [];
        for (let left = 0; left < nodeRects.length; left++) for (let right = left + 1; right < nodeRects.length; right++) {
          if (overlaps(nodeRects[left], nodeRects[right])) nodeOverlaps.push([nodeRects[left].id, nodeRects[right].id]);
        }
        const textOverflowDetails = [...document.querySelectorAll('.svg-node-copy,.branch-copy,.node-copy')]
          .filter(element => element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + 1)
          .map(element => ({
            flow: element.closest('[data-flow]')?.getAttribute('data-flow') || '',
            label: element.querySelector('b')?.textContent?.trim() || '',
            clientWidth: element.clientWidth,
            clientHeight: element.clientHeight,
            scrollWidth: element.scrollWidth,
            scrollHeight: element.scrollHeight,
          }));
        const svg = document.querySelector('.flow-diagram');
        const svgRect = svg ? rect(svg) : null;
        const edgeLabelOutOfBounds = svgRect ? [...document.querySelectorAll('.flow-diagram .edge-label-bg')]
          .map(rect).filter(value => value.left < svgRect.left - 1 || value.right > svgRect.right + 1
            || value.top < svgRect.top - 1 || value.bottom > svgRect.bottom + 1).length : 0;
        const top = document.querySelector('.top');
        const refine = document.querySelector('.refine');
        const sourceLink = document.querySelector('.source-link');
        if (sourceLink) sourceLink.click();
        let sourcePayload = null;
        try {
          sourcePayload = window.__capturedSourcePayload ? JSON.parse(window.__capturedSourcePayload) : null;
        } catch (_) {
          sourcePayload = { invalid: true };
        }
        return {
          scrollWidth: document.documentElement.scrollWidth,
          scrollHeight: document.documentElement.scrollHeight,
          innerWidth: window.innerWidth,
          innerHeight: window.innerHeight,
          svg: svgRect,
          nodeCount: nodeRects.length,
          nodeOverlaps,
          textOverflow: textOverflowDetails.length,
          textOverflowDetails,
          edgeLabelOutOfBounds,
          topVisible: Boolean(top && rect(top).top >= 0 && rect(top).bottom <= window.innerHeight),
          refineVisible: Boolean(refine && rect(refine).top >= 0 && rect(refine).bottom <= window.innerHeight),
          sourceLinkPresent: Boolean(sourceLink),
          sourcePayload,
          checks: document.querySelector('.diagram-shell')?.getAttribute('data-diagram-checks') || null,
        };
      });
      const errors = [];
      if (metrics.scrollWidth > metrics.innerWidth) errors.push('page-horizontal-overflow');
      if (!metrics.svg || metrics.svg.width < 1 || metrics.svg.height < 1) errors.push('diagram-empty');
      if (metrics.nodeCount < 1) errors.push('diagram-has-no-nodes');
      if (metrics.nodeOverlaps.length) errors.push('node-overlap');
      if (metrics.textOverflow) errors.push('node-text-overflow');
      if (metrics.edgeLabelOutOfBounds) errors.push('edge-label-out-of-bounds');
      if (!metrics.topVisible || !metrics.refineVisible) errors.push('required-controls-not-visible');
      if (metrics.sourceLinkPresent && (!metrics.sourcePayload?.path || !metrics.sourcePayload?.line)) {
        errors.push('source-link-payload-missing');
      }
      if (!['9/9', '12/12'].includes(metrics.checks)) errors.push('diagram-acceptance-missing');
      const screenshot = `${viewport.name}-${theme}.png`;
      await page.screenshot({ path: path.join(outputDirectory, screenshot), fullPage: false });
      results.push({ viewport, theme, screenshot, metrics, errors });
      await page.close();
    }
  }
} finally {
  await browser.close();
}

const failed = results.some(result => result.errors.length > 0);
const receipt = {
  schema: 'codebecause-report-visual-acceptance/v1',
  status: failed ? 'fail' : 'pass',
  visualReview: 'pending',
  artifact: { path: htmlPath, sha256: sourceSha256, bytes: source.byteLength },
  checks: results,
};
fs.writeFileSync(path.join(outputDirectory, 'receipt.json'), `${JSON.stringify(receipt, null, 2)}\n`);
console.log(JSON.stringify(receipt, null, 2));
process.exit(failed ? 1 : 0);
