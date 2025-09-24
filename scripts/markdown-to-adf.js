#!/usr/bin/env node

try {
  const { markdownToAdf } = require('marklassian');

  let markdown = '';
  process.stdin.setEncoding('utf8');

  process.stdin.on('readable', () => {
    let chunk;
    while ((chunk = process.stdin.read()) !== null) {
      markdown += chunk;
    }
  });

  process.stdin.on('end', () => {
    try {
      if (markdown.trim() === '') {
        // Return empty ADF document for empty input
        console.log(JSON.stringify({
          version: 1,
          type: "doc",
          content: []
        }));
        process.exit(0);
      }

      const adf = markdownToAdf(markdown);
      console.log(JSON.stringify(adf));
      process.exit(0);
    } catch (error) {
      console.error(JSON.stringify({
        error: error.message,
        input: markdown
      }));
      process.exit(1);
    }
  });

} catch (error) {
  if (error.code === 'MODULE_NOT_FOUND') {
    console.error(JSON.stringify({
      error: "marklassian not installed. Run: npm install marklassian"
    }));
  } else {
    console.error(JSON.stringify({
      error: error.message
    }));
  }
  process.exit(1);
}