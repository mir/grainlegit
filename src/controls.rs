use serde::{Deserialize, Serialize};
use std::sync::OnceLock;

#[repr(C)]
#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Serialize)]
#[serde(default)]
pub struct Settings {
    pub grain: f64,
    pub grain_size: f64,
    pub grain_load: f64,
    pub coarse_fraction: f64,
    pub layer_size: [f64; 3],
    pub negative_width: f64,
    pub exposure: f64,
    pub development: f64,
    pub enlargement: f64,
    pub enlarger_blur: f64,
    pub print_exposure: f64,
    pub print_lights: [f64; 3],
    pub print_grain: f64,
    pub scan_blur: f64,
    pub scan_aperture: f64,
    pub grain_colour: f64,
    /// Contrast of the negative's grain around its mean light; 1 is the simulated amount.
    pub grain_strength: f64,
    /// Grain balance from shadows (-1, up to 2× there) to highlights (+1, up to 2× there).
    pub grain_tone: f64,
    /// Blend from the generic negative (0) to the selected stock (1); up to 4 exaggerates the
    /// stock's tone and colour.
    pub stock_strength: f64,
    pub print_medium: u32,
    pub negative_stock: u32,
}

pub const MEDIA: [&str; 3] = ["direct", "paper", "film"];

#[derive(Deserialize, Serialize)]
pub struct Stock {
    pub id: String,
    pub name: String,
    /// The top of this stock's strength slider, and the strength its picker preview uses. Stocks
    /// whose look is already strong blow out well before 4.
    #[serde(default = "full_strength_range")]
    pub max_strength: f64,
}

fn full_strength_range() -> f64 {
    4.0
}

pub fn negative_stocks() -> &'static [Stock] {
    #[derive(Deserialize)]
    struct Profiles {
        negative_stocks: Vec<Stock>,
    }

    static STOCKS: OnceLock<Vec<Stock>> = OnceLock::new();
    STOCKS.get_or_init(|| {
        serde_json::from_str::<Profiles>(include_str!("../profiles/emulsions.json"))
            .expect("bundled emulsion profiles")
            .negative_stocks
    })
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            grain: 1.0,
            grain_size: 1.0,
            grain_load: 1.0,
            coarse_fraction: -1.0,
            layer_size: [1.0; 3],
            negative_width: 36.0,
            exposure: 0.0,
            development: 0.0,
            enlargement: 6.0,
            enlarger_blur: 0.0,
            print_exposure: 0.0,
            print_lights: [0.0; 3],
            print_grain: 1.0,
            scan_blur: 0.0,
            scan_aperture: 1.0,
            grain_colour: 1.0,
            grain_strength: 1.0,
            grain_tone: 0.0,
            stock_strength: 1.0,
            print_medium: 0,
            negative_stock: 0,
        }
    }
}

impl Settings {
    pub fn effective_negative_stock(&self) -> u32 {
        if self.stock_strength == 0.0 {
            0
        } else {
            self.negative_stock
        }
    }

    pub fn bypass(&self) -> bool {
        self.grain <= 1e-6
            && self.effective_negative_stock() == 0
            && self.exposure == 0.0
            && self.development == 0.0
            && self.print_medium == 0
            && self.scan_blur == 0.0
            && self.scan_aperture == 1.0
    }

    pub fn validate(&self) -> Result<(), String> {
        if !(0.0..=4.0).contains(&self.stock_strength) {
            return Err("Stock strength must be between 0 and 4".into());
        }
        if self.print_medium == 0
            && (self.enlargement != 6.0
                || self.enlarger_blur != 0.0
                || self.print_exposure != 0.0
                || self.print_lights != [0.0; 3]
                || self.print_grain != 1.0)
        {
            return Err(
                "select --print-medium paper or film to use optical printing controls".into(),
            );
        }
        Ok(())
    }

    pub fn set(&mut self, flag: &str, value: &str) -> Result<(), String> {
        match flag {
            "--negative-stock" => {
                self.negative_stock = if value == "generic" {
                    0
                } else {
                    negative_stocks()
                        .iter()
                        .position(|stock| stock.id == value)
                        .map(|index| index as u32 + 1)
                        .ok_or_else(|| format!("unknown negative stock: {value}"))?
                };
            }
            "--grain" => self.grain = bounded(flag, value, 0.0, 3.0)?,
            "--grain-size" => self.grain_size = bounded(flag, value, 0.1, 16.0)?,
            "--grain-load" => self.grain_load = bounded(flag, value, 0.1, 8.0)?,
            "--grain-colour" => self.grain_colour = bounded(flag, value, 0.0, 1.0)?,
            "--coarse-fraction" => self.coarse_fraction = bounded(flag, value, 0.0, 1.0)?,
            "--layer-size" => self.layer_size = triple(flag, value, 0.25, 4.0)?,
            "--negative-width" => self.negative_width = bounded(flag, value, 8.0, 100.0)?,
            "--exposure" => self.exposure = bounded(flag, value, -4.0, 4.0)?,
            "--development" => self.development = bounded(flag, value, -2.0, 2.0)?,
            "--print-medium" => {
                self.print_medium = MEDIA
                    .iter()
                    .position(|name| *name == value)
                    .ok_or("--print-medium requires direct, paper, or film")?
                    as u32;
            }
            "--enlargement" => self.enlargement = bounded(flag, value, 1.0, 30.0)?,
            "--enlarger-blur" => self.enlarger_blur = bounded(flag, value, 0.0, 150.0)?,
            "--print-exposure" => self.print_exposure = bounded(flag, value, -4.0, 4.0)?,
            "--print-lights" => self.print_lights = triple(flag, value, -4.0, 4.0)?,
            "--print-grain" => self.print_grain = bounded(flag, value, 0.0, 4.0)?,
            "--scan-blur" => self.scan_blur = bounded(flag, value, 0.0, 8.0)?,
            "--scan-aperture" => self.scan_aperture = bounded(flag, value, 1.0, 8.0)?,
            _ => return Err(format!("unknown option: {flag}")),
        }
        Ok(())
    }
}

fn bounded(flag: &str, value: &str, low: f64, high: f64) -> Result<f64, String> {
    value
        .trim()
        .parse()
        .ok()
        .filter(|value| (low..=high).contains(value))
        .ok_or_else(|| format!("{flag} must be between {low} and {high}"))
}

fn triple(flag: &str, value: &str, low: f64, high: f64) -> Result<[f64; 3], String> {
    let values = value
        .split(',')
        .map(|part| bounded(flag, part, low, high))
        .collect::<Result<Vec<_>, _>>()?;
    values
        .try_into()
        .map_err(|_| format!("{flag} requires three comma-separated R,G,B values"))
}

#[cfg(test)]
mod tests {
    use super::{negative_stocks, Settings};

    #[test]
    fn bundled_stock_ids_match_renderer_order() {
        let stocks = negative_stocks();
        let stock_ids: Vec<_> = stocks.iter().map(|stock| stock.id.as_str()).collect();
        assert_eq!(
            stock_ids,
            [
                "portra-400",
                "gold-200",
                "ektar-100",
                "vision3-500t",
                "portra-160",
                "portra-800",
                "ultramax-400",
                "superia-xtra-400",
            ]
        );

        let capped: Vec<_> = stocks
            .iter()
            .filter(|stock| stock.max_strength != 4.0)
            .map(|stock| (stock.id.as_str(), stock.max_strength))
            .collect();
        assert_eq!(capped, [("vision3-500t", 2.0), ("portra-800", 2.0)]);

        let mut settings = Settings::default();
        for (index, id) in stock_ids.iter().enumerate() {
            settings.set("--negative-stock", id).unwrap();
            assert_eq!(settings.negative_stock, index as u32 + 1);
        }
    }

    #[test]
    fn stock_strength_defaults_to_full_in_saved_edits() {
        let mut settings: Settings =
            serde_json::from_str(r#"{"negative_stock":1,"grain":0}"#).unwrap();
        assert_eq!(settings.stock_strength, 1.0);
        assert!(!settings.bypass());
        settings.stock_strength = 0.0;
        assert!(settings.bypass());
        settings.stock_strength = 4.0;
        assert!(settings.validate().is_ok());
        settings.stock_strength = 4.01;
        assert!(settings.validate().is_err());
    }
}
