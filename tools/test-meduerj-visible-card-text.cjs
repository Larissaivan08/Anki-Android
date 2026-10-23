// SPDX-License-Identifier: GPL-3.0-or-later
// Development-only browser regression test. Requires playwright-core and Chrome/Chromium.
// PLAYWRIGHT_MODULE can point to an existing playwright-core installation.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');

(async () => {
    const script = fs.readFileSync(path.join(__dirname,
        '../AnkiDroid/src/main/assets/scripts/meduerj-visible-card-text.js'), 'utf8');
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROME_PATH});
    try {
        const page = await browser.newPage();
        const cases = [
            ['<div id="qa">Qual a conduta? <span style="display:none">RESPOSTA SECRETA</span></div>', 'Qual a conduta?'],
            ['<div id="qa">Pergunta <span hidden>SEGREDO</span><span style="visibility:hidden">SEGREDO</span>' +
                '<div style="opacity:0">SEGREDO</div><span aria-hidden="true">SEGREDO</span></div>', 'Pergunta'],
            ['<div id="qa">Leia isto.<button>Reproduzir</button><input value="segredo">' +
                '<script>window.answer="RESPOSTA SECRETA";</script><style>.x{color:red}</style></div>', 'Leia isto.'],
            ['<div id="qa">A deficiência de <span class="cloze">[...]</span> causa anemia.</div>', 'A deficiência de [...] causa anemia.'],
            ['<div id="qa">Pergunta<hr id="answer">Resposta revelada: ação e revisão.</div>', 'Pergunta Resposta revelada: ação e revisão.'],
            ['<div id="qa">Visível<div style="height:0;overflow:hidden"><span>SEGREDO</span></div></div>', 'Visível'],
            ['<div>Sem cartão</div>', ''],
            ['<div id="qa">hiper<b>tensão</b> arterial</div>', 'hipertensão arterial'],
            ['<div id="qa"><b>anti</b> <i>hipertensivo</i></div>', 'anti hipertensivo'],
            ['<div id="qa"><div>Pergunta</div><div>Resposta<br>Visível</div></div>', 'Pergunta Resposta Visível'],
        ];
        for (const [html, expected] of cases) {
            await page.setContent(html);
            const before = await page.content();
            assert.equal(await page.evaluate(script), expected);
            assert.equal(await page.content(), before, 'Speech extraction must not alter card HTML');
        }
        // Exercise the existing legacy renderer's real lifecycle code with delayed MathJax.
        const legacy = fs.readFileSync(path.join(__dirname,
            '../AnkiDroid/src/main/assets/scripts/card.js'), 'utf8');
        const lifecycle = legacy.slice(legacy.indexOf('function _runHook(arr)'),
            legacy.indexOf('/* Add function 2 hook'));
        await page.setContent('<div id="qa" class="card mathjax-needs-to-render">Texto com fórmula</div>');
        await page.evaluate('var resizeDone = true; ' + lifecycle);
        await page.evaluate(() => {
            window.cardWasShown = false;
            window.readingReady = false;
            window.MathJax = {
                startup: {promise: Promise.resolve()},
                typesetPromise: () => new Promise(resolve => { window.finishMathjax = resolve; }),
            };
            onShownHook.push(() => { window.cardWasShown = true; });
            onShownHook.push(() => new Promise(resolve => { window.finishTemplateHook = resolve; }));
            onPageFinished().then(() => { window.readingReady = true; });
        });
        await page.waitForFunction(() => typeof window.finishMathjax === 'function');
        assert.equal(await page.evaluate(() => window.cardWasShown), false,
            'Card must not be announced while MathJax is still rendering');
        await page.evaluate(() => window.finishMathjax());
        await page.waitForFunction(() => window.cardWasShown);
        assert.equal(await page.evaluate(() => window.readingReady), false,
            'Native reader must also wait for asynchronous template hooks');
        await page.evaluate(() => window.finishTemplateHook());
        await page.waitForFunction(() => window.readingReady);
        console.log(`PASS: ${cases.length} visible-card cases and delayed-render lifecycle`);
    } finally {
        await browser.close();
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
