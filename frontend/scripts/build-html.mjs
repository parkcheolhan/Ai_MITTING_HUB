import {build} from 'esbuild';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
const pages=process.argv.includes('--pages');
const destination=pages?'pages':'standalone';
const result=await build({entryPoints:['src/main.tsx'],bundle:true,write:false,outfile:'app.js',format:'iife',minify:true,target:'es2022',define:{'process.env.NODE_ENV':'"production"','globalThis.MOA_STANDALONE':String(!pages),'globalThis.MOA_PAGES':String(pages)},legalComments:'none'});
const js=result.outputFiles.find(f=>f.path.endsWith('.js')).text.replace(/<\/script/gi,'<\\/script');
// Use installed system fonts so the standalone file makes no CDN requests.
// A quoted font URL can contain semicolons (e.g. wght@400;500); consume the
// entire URL/string rather than treating its first semicolon as the terminator.
const css=(result.outputFiles.find(f=>f.path.endsWith('.css'))?.text || '').replace(/@import\s*(?:url\([^)]*\)|"[^"]*"|'[^']*')\s*;/gi,'');
const template=await readFile('index.html','utf8');
const html=template.replace('</head>',`<style>${css.replace(/<\/style/gi,'<\\/style')}</style></head>`).replace('<script type="module" src="/src/main.tsx"></script>',()=>`<script>${js}</script>`);
await mkdir(destination,{recursive:true});
await writeFile(`${destination}/index.html`,html);
await writeFile(`${destination}/.nojekyll`,'');
console.log(`${destination}/index.html (${Buffer.byteLength(html).toLocaleString()} bytes)`);
