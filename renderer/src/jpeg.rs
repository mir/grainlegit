use jni::objects::JObject;
use jni::JNIEnv;
use jpeg_encoder::{rgb_to_ycbcr, Encoder, ImageBuffer, JpegColorType, SamplingFactor};
use std::cell::{Cell, RefCell};
use std::fs::File;
use std::io::{self, BufReader, BufWriter, Read, Seek, SeekFrom, Write};

struct Rows<'local, 'borrow> {
    file: RefCell<BufReader<File>>,
    next_row: Cell<u16>,
    row: RefCell<Vec<u8>>,
    env: RefCell<JNIEnv<'local>>,
    cancel: &'borrow JObject<'local>,
    failed: &'borrow Cell<Option<&'static str>>,
    width: u16,
    height: u16,
}

impl ImageBuffer for Rows<'_, '_> {
    fn get_jpeg_color_type(&self) -> JpegColorType {
        JpegColorType::Ycbcr
    }
    fn width(&self) -> u16 {
        self.width
    }
    fn height(&self) -> u16 {
        self.height
    }

    fn fill_buffers(&self, y: u16, channels: &mut [Vec<u8>; 4]) {
        let mut row = self.row.borrow_mut();
        let mut file = self.file.borrow_mut();
        if self.failed.get().is_none() {
            if y % 8 == 0
                && self
                    .env
                    .borrow_mut()
                    .call_method(self.cancel, "get", "()Z", &[])
                    .and_then(|value| value.z())
                    .unwrap_or(true)
            {
                self.failed.set(Some("Export cancelled"));
            } else {
                let offset = u64::from(y) * u64::from(self.width) * 3;
                let positioned = if self.next_row.get() == y {
                    Ok(())
                } else {
                    file.seek(SeekFrom::Start(offset)).map(|_| ())
                };
                self.next_row.set(y.saturating_add(1));
                if positioned.and_then(|_| file.read_exact(&mut row)).is_err() {
                    self.failed
                        .set(Some("Could not read the rendered JPEG rows"));
                }
            }
        }
        if self.failed.get().is_some() {
            row.fill(0);
        }
        for pixel in row.chunks_exact(3) {
            let (y, cb, cr) = rgb_to_ycbcr(pixel[0], pixel[1], pixel[2]);
            channels[0].push(y);
            channels[1].push(cb);
            channels[2].push(cr);
        }
    }
}

struct ExportWriter<'a> {
    file: BufWriter<File>,
    failed: &'a Cell<Option<&'static str>>,
}

impl Write for ExportWriter<'_> {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if let Some(error) = self.failed.get() {
            return Err(io::Error::other(error));
        }
        self.file.write(bytes)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.file.flush()
    }
}

pub fn encode<'local>(
    env: &JNIEnv<'local>,
    cancel: &JObject<'local>,
    raw: &str,
    path: &str,
    width: i32,
    height: i32,
) -> Result<(), String> {
    let width = u16::try_from(width).map_err(|_| "JPEG width is out of range")?;
    let height = u16::try_from(height).map_err(|_| "JPEG height is out of range")?;
    let file = File::open(raw).map_err(|error| error.to_string())?;
    let failed = Cell::new(None);
    let mut output = ExportWriter {
        file: BufWriter::new(File::create(path).map_err(|error| error.to_string())?),
        failed: &failed,
    };
    let mut encoder = Encoder::new(&mut output, 95);
    encoder.set_sampling_factor(SamplingFactor::F_1_1);
    encoder
        .add_icc_profile(include_bytes!("srgb.icc"))
        .map_err(|error| error.to_string())?;
    let result = encoder.encode_image(Rows {
        file: RefCell::new(BufReader::new(file)),
        next_row: Cell::new(0),
        row: RefCell::new(vec![0; usize::from(width) * 3]),
        env: RefCell::new(unsafe { env.unsafe_clone() }),
        cancel,
        failed: &failed,
        width,
        height,
    });
    if let Some(error) = failed.get() {
        return Err(error.into());
    }
    result.map_err(|error| error.to_string())?;
    output.flush().map_err(|error| error.to_string())
}
