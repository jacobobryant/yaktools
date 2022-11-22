# Yaktools

Reading-related utilities library used by [Yakread](https://yakread.com/).
Currently has one public function, `parse-epub-dir`. Eventually I'd like to add
functions to replace Yakread's current usage of
[Readability](https://github.com/mozilla/readability) and
[Juice](https://github.com/Automattic/juice), which are both JS libs and thus
require more resources to use than equivalent JVM-based libs would.

## Contributing

To get started, first get yourself a DRM-free epub file and unzip the contents
into a `test-epub` directory:

```
mkdir test-epub
cp /some/epub/file test-epub/file.epub
cd test-epub
unzip file.epub
```

Then jack in from your editor (or run `clj -M:nrepl` and connect to the given
port) and evaluate the `let` form at the bottom of `src/com/yakread/tools.clj`.

See the [issues](issues).
