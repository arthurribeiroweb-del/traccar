// Conversor SCDB (CSDB_csv) -> GeoJSON para uso no Traccar Web.
//
// Uso sugerido (na pasta c:\PROJETOS\traccar):
//   node radars/convert-scdb-to-geojson.js
//
// Entrada:
//   c:\PROJETOS\traccar\radars\originais\SCDB_csv\*.csv
//     - Arquivos separados por tipo/velocidade (SCDB_Speed_60.csv, SCDB_Redlight_40.csv, etc.)
//     - Cada linha no formato: lon,lat,"dir","[id]"
//
// Saída:
//   c:\PROJETOS\traccar\traccar-web\public\radars\scdb-radars-br.geojson
//
// O GeoJSON gerado é um FeatureCollection de pontos com propriedades:
//   - speedKph: número (pode ser null para alguns tipos, como Tunnel ou Section_End)
//   - category: SPEED, REDLIGHT, SECTION, SECTION_END, TUNNEL, SPEED_VARIABLE, etc.
//   - direction: N, S, E, W ou null
//   - source: sempre "SCDB"
//   - externalId: id externo da SCDB (sem colchetes)
//   - file: nome do arquivo de origem (ex.: SCDB_Speed_60.csv)

/* eslint-disable no-console */

const fs = require('fs');
const path = require('path');

const ROOT_DIR = path.resolve(__dirname, '..'); // c:\PROJETOS\traccar
const INPUT_DIR = path.resolve(__dirname, 'originais', 'SCDB_csv');
const OUTPUT_DIR = path.resolve(ROOT_DIR, 'traccar-web', 'public', 'radars');
const OUTPUT_FILE = path.resolve(OUTPUT_DIR, 'scdb-radars-br.geojson');

function parseScdbFilename(fileName) {
  const base = fileName.replace(/\.csv$/i, '');
  if (!base.startsWith('SCDB_')) {
    return null;
  }

  const rest = base.slice('SCDB_'.length); // ex.: Speed_60, Redlight_40, Section_End
  const parts = rest.split('_');
  const first = (parts[0] || '').toUpperCase();
  const second = parts[1] || '';

  let category = null;
  let speedKph = null;

  const numericSecond = /^\d+$/.test(second) ? Number(second) : null;

  switch (first) {
    case 'SPEED':
      if (numericSecond != null) {
        category = 'SPEED';
        speedKph = numericSecond;
      } else if (second.toLowerCase() === 'variable') {
        category = 'SPEED_VARIABLE';
      } else {
        category = 'SPEED';
      }
      break;
    case 'REDLIGHT':
      category = 'REDLIGHT';
      if (numericSecond != null) {
        speedKph = numericSecond;
      }
      break;
    case 'SECTION':
      if (second.toUpperCase() === 'END') {
        category = 'SECTION_END';
      } else if (numericSecond != null) {
        category = 'SECTION';
        speedKph = numericSecond;
      } else {
        category = 'SECTION';
      }
      break;
    case 'TUNNEL':
      category = 'TUNNEL';
      break;
    default:
      category = first || 'UNKNOWN';
      break;
  }

  return { category, speedKph };
}

function parseCsvLine(line) {
  const trimmed = line.trim();
  if (!trimmed || trimmed.startsWith('#')) {
    return null;
  }

  const parts = trimmed.split(',');
  if (parts.length < 2) {
    return null;
  }

  const lon = Number.parseFloat(parts[0]);
  const lat = Number.parseFloat(parts[1]);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return null;
  }

  const raw3 = (parts[2] || '').trim().replace(/^"|"$/g, '');
  const raw4 = (parts[3] || '').trim().replace(/^"|"$/g, '');

  let direction = null;
  let externalId = null;

  if (/^[NSEW]$/i.test(raw3)) {
    direction = raw3.toUpperCase();
    externalId = raw4 || null;
  } else {
    externalId = raw3 || raw4 || null;
  }

  if (externalId) {
    externalId = externalId.replace(/^\[|\]$/g, '');
  }

  return {
    latitude: lat,
    longitude: lon,
    direction,
    externalId,
  };
}

function main() {
  if (!fs.existsSync(INPUT_DIR)) {
    console.error(`Pasta de entrada não encontrada: ${INPUT_DIR}`);
    process.exit(1);
  }

  fs.mkdirSync(OUTPUT_DIR, { recursive: true });

  const files = fs.readdirSync(INPUT_DIR).filter((file) => file.toLowerCase().endsWith('.csv'));
  if (!files.length) {
    console.error(`Nenhum arquivo CSV encontrado em: ${INPUT_DIR}`);
    process.exit(1);
  }

  const features = [];
  const categoryCounts = {};

  files.forEach((fileName) => {
    const meta = parseScdbFilename(fileName);
    if (!meta) {
      console.warn(`Ignorando arquivo com nome inesperado: ${fileName}`);
      return;
    }

    const { category, speedKph } = meta;
    const filePath = path.resolve(INPUT_DIR, fileName);
    const content = fs.readFileSync(filePath, 'utf8');
    const lines = content.split(/\r?\n/);

    lines.forEach((line) => {
      const parsed = parseCsvLine(line);
      if (!parsed) {
        return;
      }

      const {
        latitude, longitude, direction, externalId,
      } = parsed;

      features.push({
        type: 'Feature',
        geometry: {
          type: 'Point',
          coordinates: [longitude, latitude],
        },
        properties: {
          speedKph,
          category,
          direction: direction || null,
          source: 'SCDB',
          externalId: externalId || null,
          file: fileName,
        },
      });

      const key = category || 'UNKNOWN';
      categoryCounts[key] = (categoryCounts[key] || 0) + 1;
    });
  });

  const geojson = {
    type: 'FeatureCollection',
    features,
  };

  fs.writeFileSync(OUTPUT_FILE, JSON.stringify(geojson, null, 2), 'utf8');

  console.log(`Gerado: ${OUTPUT_FILE}`);
  console.log(`Total de radares: ${features.length}`);
  console.log('Por categoria:', categoryCounts);
}

if (require.main === module) {
  main();
}

