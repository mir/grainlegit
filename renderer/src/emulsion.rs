use grainlegit::Settings;
use serde::Deserialize;
use std::sync::LazyLock;

const POPULATIONS: usize = 6;
const ALIAS_SIZE: usize = 128;
pub const MAX_HALO: usize = 256;
pub const PRINT_SEED_MASK: u64 = 0xd1b54a32d192ed03;

#[derive(Deserialize)]
struct Profiles {
    negative: Material,
    negative_stocks: Vec<Material>,
    paper: Material,
    film: Material,
}

#[derive(Clone, Copy, Deserialize)]
struct Material {
    image_density_range: f64,
    #[serde(default)]
    exposure_knee: f64,
    #[serde(default)]
    shoulder_range: f64,
    #[serde(default)]
    exposure_midpoint: f64,
    #[serde(default)]
    contrast: f64,
    #[serde(default)]
    development_sensitivity: [f64; 3],
    #[serde(default)]
    exposure_balance_ev: [f64; 3],
    #[serde(default = "one")]
    saturation: f64,
    fog_density: f64,
    layers: [[[f64; 3]; 2]; 3],
}

fn one() -> f64 {
    1.0
}

static PROFILES: LazyLock<Profiles> = LazyLock::new(|| {
    serde_json::from_str(include_str!("../../profiles/emulsions.json"))
        .expect("bundled emulsion profiles")
});

fn material(settings: &Settings, print: bool) -> Material {
    if print {
        return if settings.print_medium == 1 {
            PROFILES.paper
        } else {
            PROFILES.film
        };
    }
    let generic = PROFILES.negative;
    let index = settings.effective_negative_stock();
    if index == 0 {
        return generic;
    }
    let stock = PROFILES.negative_stocks[index as usize - 1];
    if settings.stock_strength == 1.0 {
        return stock;
    }

    // Strengths above 1 (up to 4) exaggerate the stock's
    // tone and colour. Grain clouds stay at the real stock: extrapolated cloud fractions and dye
    // loads would leave their valid ranges.
    let strength = settings.stock_strength;
    let blend_by = |strength: f64, base: f64, target: f64| base + (target - base) * strength;
    let blend = |base: f64, target: f64| blend_by(strength, base, target);
    let blend_triplet = |base: [f64; 3], target: [f64; 3]| {
        std::array::from_fn(|index| blend(base[index], target[index]).max(0.0))
    };
    let grain_strength = strength.min(1.0);
    Material {
        fog_density: blend(generic.fog_density, stock.fog_density).max(0.0),
        image_density_range: blend(generic.image_density_range, stock.image_density_range).max(0.1),
        exposure_knee: blend(generic.exposure_knee, stock.exposure_knee).max(0.0),
        shoulder_range: blend(generic.shoulder_range, stock.shoulder_range).max(0.05),
        saturation: blend(generic.saturation, stock.saturation).max(0.0),
        exposure_balance_ev: std::array::from_fn(|index| {
            blend(
                generic.exposure_balance_ev[index],
                stock.exposure_balance_ev[index],
            )
        }),
        development_sensitivity: blend_triplet(
            generic.development_sensitivity,
            stock.development_sensitivity,
        ),
        layers: std::array::from_fn(|layer| {
            std::array::from_fn(|cloud| {
                std::array::from_fn(|index| {
                    blend_by(
                        grain_strength,
                        generic.layers[layer][cloud][index],
                        stock.layers[layer][cloud][index],
                    )
                })
            })
        }),
        ..generic
    }
}

fn active(settings: &Settings, print: bool) -> bool {
    if print {
        settings.print_grain > 1e-6
    } else {
        settings.grain > 1e-6
    }
}

fn sigma(edge: u32, settings: &Settings, print: bool, layer: usize, diameter: f64) -> f64 {
    let scale = 2.0 * f64::from(edge) / (1000.0 * settings.negative_width);
    if print {
        diameter * scale / settings.enlargement
    } else {
        diameter * scale * settings.grain.sqrt() * settings.grain_size * settings.layer_size[layer]
    }
}

fn cloud_radius(sigma: f64) -> usize {
    if sigma == 0.0 {
        0
    } else {
        (4.0 * sigma).ceil().max(1.0) as usize
    }
}

/// Reference photo area for Auto grain: the editor's viewport on a 1200 × 2608 px phone with
/// the bottom panel open. Fit shrinks photos to this size and never enlarges them.
const AUTO_VIEW: [f64; 2] = [1200.0, 1500.0];
/// Luma-weighted fine-cloud sigma, in screen pixels at Fit, that viewers picked as just clearly
/// visible across 0.3–200 MP photos: their 1.1 px mean over the layers of the earlier Generic
/// profile (2.2, 2.0, 2.35 µm) is 1.058 px luma-weighted.
const AUTO_SCREEN_SIGMA: f64 =
    1.1 * (0.2126 * 2.2 + 0.7152 * 2.0 + 0.0722 * 2.35) / ((2.2 + 2.0 + 2.35) / 3.0);
/// Visibility is mostly luminance, so the fine-cloud reference weights layers by Rec. 709 luma.
const LUMA: [f64; 3] = [0.2126, 0.7152, 0.0722];

/// Grain size multiplier that makes the negative's fine clouds visible at Fit.
///
/// The result accounts for the stock's cloud sizes, the format width, the per-layer size
/// multipliers and the grain amount, and stays within the renderer's halo limit.
pub fn auto_grain_size(width: u32, height: u32, settings: &Settings) -> f64 {
    let [width, height] = [f64::from(width.max(1)), f64::from(height.max(1))];
    let fit = (AUTO_VIEW[0] / width).min(AUTO_VIEW[1] / height).min(1.0);
    let target = AUTO_SCREEN_SIGMA / fit;
    let unit = Settings {
        grain: settings.grain.max(1e-6),
        grain_size: 1.0,
        ..*settings
    };
    let edge = width.max(height) as u32;
    let layers = &material(&unit, false).layers;
    let fine = (0..3)
        .map(|layer| LUMA[layer] * sigma(edge, &unit, false, layer, layers[layer][0][0]))
        .sum::<f64>();
    let widest = (0..3)
        .flat_map(|layer| layers[layer].iter().map(move |cloud| (layer, cloud[0])))
        .map(|(layer, diameter)| sigma(edge, &unit, false, layer, diameter))
        .fold(0.0, f64::max);
    let limit = (MAX_HALO as f64 - 1.0) / (4.0 * widest);
    (target / fine).min(limit).max(0.1)
}

pub fn enlarger_sigma(edge: u32, settings: &Settings) -> f64 {
    settings.enlarger_blur * f64::from(edge)
        / (1000.0 * settings.negative_width * settings.enlargement)
}

pub fn padding(edge: u32, settings: &Settings) -> Result<u32, String> {
    let mut total = 0;
    for print in [false, true] {
        if (print && settings.print_medium == 0) || !active(settings, print) {
            continue;
        }
        let mut halo = 0;
        for (layer, clouds) in material(settings, print).layers.iter().enumerate() {
            for &[size, _, _] in clouds {
                halo = halo.max(cloud_radius(sigma(edge, settings, print, layer, size)));
            }
        }
        if halo > MAX_HALO {
            return Err("The source is too large for the selected grain and film format".into());
        }
        total += halo.div_ceil(2) as u32;
    }
    if settings.print_medium != 0 {
        total += (4.0 * enlarger_sigma(edge, settings)).ceil() as u32;
    }
    total += (4.0 * settings.scan_blur).ceil() as u32;
    total += ((settings.scan_aperture - 1.0) / 2.0).ceil() as u32;
    Ok(total)
}

pub fn scan_curve() -> [f32; 3] {
    let profile = &PROFILES.negative;
    [
        profile.image_density_range as f32,
        profile.exposure_knee as f32,
        (1.0 / profile.exposure_knee).asinh() as f32,
    ]
}

pub struct Emulsion {
    pub words: Vec<u32>,
    pub halo: u32,
    pub tones: u32,
    pub stochastic: bool,
    pub saturation: f32,
    pub base_light: f32,
}

impl Emulsion {
    pub fn new(edge: u32, settings: &Settings, print: bool, reference: bool) -> Self {
        let profile = material(settings, print);
        let active = active(settings, print);
        let stochastic = active && !reference;
        let tones = if print { 4096 } else { 256 };
        let load = if print {
            settings.print_grain
        } else {
            settings.grain_load * (settings.development / 3.0).exp2()
        };
        let rate_start = POPULATIONS * 3;
        let alias_start = rate_start + POPULATIONS * tones;
        let mut words = vec![
            0;
            alias_start
                + if stochastic {
                    POPULATIONS * tones * ALIAS_SIZE
                } else {
                    0
                }
        ];
        let mut halo = 0;
        for (layer, clouds) in profile.layers.iter().enumerate() {
            let exposure = if print {
                settings.print_exposure + settings.print_lights[layer]
            } else {
                settings.exposure + profile.exposure_balance_ev[layer]
            };
            let gain = exposure.exp2();
            let slope =
                (settings.development * profile.development_sensitivity[layer] / 2.0).exp2();
            let densities: Vec<_> = (0..tones)
                .map(|tone| {
                    let signal = tone as f64 / (tones - 1) as f64;
                    f64::from(target_density(&profile, signal, gain, slope, print) as f32)
                })
                .collect();
            for (population, &[size, peak, fraction]) in clouds.iter().enumerate() {
                let cloud = 2 * layer + population;
                let sigma = if active {
                    sigma(edge, settings, print, layer, size)
                } else {
                    0.0
                };
                let radius = cloud_radius(sigma);
                halo = halo.max(radius);
                let weights = gaussian(sigma, radius);
                let dye_load = if active {
                    peak * load * 2.0 * std::f64::consts::PI * sigma * sigma
                } else {
                    1.0
                };
                let fraction = if !active {
                    f64::from(population == 0)
                } else if !print && settings.coarse_fraction >= 0.0 {
                    if population == 1 {
                        settings.coarse_fraction
                    } else {
                        1.0 - settings.coarse_fraction
                    }
                } else {
                    fraction
                };
                let events = if !active {
                    fraction
                } else if reference {
                    fraction / dye_load
                } else {
                    let log_ten = std::f64::consts::LN_10;
                    let mut mass = 0.0;
                    for &x in &weights {
                        for &y in &weights {
                            mass -= (-log_ten * dye_load * x * y).exp_m1();
                        }
                    }
                    fraction * log_ten / mass
                };
                words[cloud * 3] = radius as u32;
                words[cloud * 3 + 1] = words.len() as u32;
                words[cloud * 3 + 2] = (dye_load as f32).to_bits();
                words.extend(weights.iter().map(|&weight| (weight as f32).to_bits()));
                for (tone, &density) in densities.iter().enumerate() {
                    let row = cloud * tones + tone;
                    let rate = (density * events) as f32;
                    words[rate_start + row] = rate.to_bits();
                    if stochastic && rate > 0.0 && rate <= 64.0 {
                        let offset = alias_start + row * ALIAS_SIZE;
                        fill_aliases(f64::from(rate), &mut words[offset..offset + ALIAS_SIZE]);
                    }
                }
            }
        }

        let fog = if print || settings.print_medium != 0 {
            profile.fog_density
        } else {
            0.0
        };
        Self {
            words,
            halo: halo as u32,
            tones: tones as u32,
            stochastic,
            saturation: profile.saturation as f32,
            base_light: 10_f32.powf(-(fog as f32)),
        }
    }
}

fn target_density(profile: &Material, signal: f64, gain: f64, slope: f64, print: bool) -> f64 {
    if print {
        let response = (signal * gain / profile.exposure_midpoint).powf(profile.contrast);
        return profile.image_density_range * response / (1.0 + response);
    }
    let linear = if signal <= 0.04045 {
        signal / 12.92
    } else {
        ((signal + 0.055) / 1.055).powf(2.4)
    };
    let range = profile.image_density_range;
    let knee = profile.exposure_knee;
    let density = range * (linear * gain / knee).asinh() * slope / (1.0 / knee).asinh();
    if density > range {
        range - profile.shoulder_range * (-(density - range) / profile.shoulder_range).exp_m1()
    } else {
        density
    }
}

pub fn optics(sigma: f64, aperture: f64) -> Vec<f32> {
    let radius = (4.0 * sigma).ceil() as usize;
    let box_radius = ((aperture - 1.0) / 2.0).ceil() as usize;
    let gaussian = gaussian(sigma, radius);
    let mut result = vec![0.0; 2 * (radius + box_radius) + 1];
    for j in 0..=2 * box_radius {
        let weight =
            (aperture / 2.0 + 0.5 - j.abs_diff(box_radius) as f64).clamp(0.0, 1.0) / aperture;
        for (i, g) in gaussian.iter().enumerate() {
            result[i + j] += (g * weight) as f32;
        }
    }
    result
}

fn gaussian(sigma: f64, radius: usize) -> Vec<f64> {
    if radius == 0 {
        return vec![1.0];
    }
    let scale = 1.0 / (std::f64::consts::SQRT_2 * sigma);
    let radius = radius as i32;
    let mut lower = 0.5 * libm::erfc((f64::from(radius) + 0.5) * scale);
    let mut weights = Vec::with_capacity((2 * radius + 1) as usize);
    for i in -radius..=radius {
        let upper = 0.5 * libm::erfc((-f64::from(i) - 0.5) * scale);
        weights.push(upper - lower);
        lower = upper;
    }
    let total: f64 = weights.iter().sum();
    for weight in &mut weights {
        *weight /= total;
    }
    weights
}

fn fill_aliases(rate: f64, aliases: &mut [u32]) {
    let mut mass = [0.0; ALIAS_SIZE];
    mass[0] = (-rate).exp();
    for i in 1..ALIAS_SIZE {
        mass[i] = mass[i - 1] * rate / i as f64;
    }
    let scale = ALIAS_SIZE as f64 / mass.iter().sum::<f64>();
    let (mut small, mut large) = ([0; ALIAS_SIZE], [0; ALIAS_SIZE]);
    let (mut slen, mut llen) = (0, 0);
    for (i, mass) in mass.iter_mut().enumerate() {
        aliases[i] = i as u32;
        *mass *= scale;
        if *mass < 1.0 {
            small[slen] = i;
            slen += 1;
        } else {
            large[llen] = i;
            llen += 1;
        }
    }
    while slen > 0 && llen > 0 {
        slen -= 1;
        llen -= 1;
        let slot = small[slen];
        let alias = large[llen];
        let cutoff = (mass[slot] * f64::from(1_u32 << 25)) as u32;
        aliases[slot] = (cutoff << 7) | alias as u32;
        mass[alias] -= 1.0 - mass[slot];
        if mass[alias] < 1.0 {
            small[slen] = alias;
            slen += 1;
        } else {
            large[llen] = alias;
            llen += 1;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fit_sigma(width: u32, height: u32, settings: &Settings) -> f64 {
        let size = auto_grain_size(width, height, settings);
        let sized = Settings {
            grain_size: size,
            ..*settings
        };
        let edge = width.max(height);
        let layers = &material(&sized, false).layers;
        let fine = (0..3)
            .map(|l| LUMA[l] * sigma(edge, &sized, false, l, layers[l][0][0]))
            .sum::<f64>();
        let fit = (1200.0 / f64::from(width))
            .min(1500.0 / f64::from(height))
            .min(1.0);
        fine * fit
    }

    #[test]
    fn auto_grain_is_visible_at_fit_for_every_resolution() {
        let settings = Settings::default();
        for [width, height] in [
            [671, 447],
            [1225, 817],
            [4243, 2829],
            [17321, 11547],
            [3000, 4000],
        ] {
            let seen = fit_sigma(width, height, &settings);
            assert!(
                (seen - AUTO_SCREEN_SIGMA).abs() < 1e-9,
                "{width}×{height}: {seen}"
            );
        }
    }

    #[test]
    fn auto_grain_matches_the_calibration_picks() {
        let settings = Settings::default();
        // Generic stock on 35 mm: about 7.5× above the viewport width, larger on small photos.
        let large = auto_grain_size(8660, 5773, &settings);
        assert!((7.0..8.0).contains(&large), "{large}");
        assert!((auto_grain_size(17321, 11547, &settings) - large).abs() < 1e-9);
        let small = auto_grain_size(671, 447, &settings);
        assert!((13.0..14.0).contains(&small), "{small}");
    }

    #[test]
    fn auto_grain_follows_amount_stock_and_format() {
        let base = Settings::default();
        let size = auto_grain_size(4000, 3000, &base);
        let doubled = auto_grain_size(4000, 3000, &Settings { grain: 4.0, ..base });
        assert!((doubled - size / 2.0).abs() < 1e-9);
        let medium = auto_grain_size(
            4000,
            3000,
            &Settings {
                negative_width: 56.0,
                ..base
            },
        );
        assert!((medium - size * 56.0 / 36.0).abs() < 1e-9);
        let ektar = Settings {
            negative_stock: 3,
            ..base
        };
        assert!(auto_grain_size(4000, 3000, &ektar) > size);
        assert!((fit_sigma(4000, 3000, &ektar) - AUTO_SCREEN_SIGMA).abs() < 1e-9);
    }

    #[test]
    fn auto_grain_stays_within_the_halo_limit() {
        let settings = Settings {
            negative_width: 245.0,
            ..Settings::default()
        };
        let size = auto_grain_size(17321, 11547, &settings);
        let sized = Settings {
            grain_size: size,
            ..settings
        };
        assert!(padding(17321, &sized).is_ok());
    }
}
