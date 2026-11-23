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

#[derive(Deserialize)]
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

fn material(settings: &Settings, print: bool) -> &Material {
    if print {
        if settings.print_medium == 1 {
            &PROFILES.paper
        } else {
            &PROFILES.film
        }
    } else if settings.negative_stock == 0 {
        &PROFILES.negative
    } else {
        &PROFILES.negative_stocks[settings.negative_stock as usize - 1]
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
                    f64::from(target_density(profile, signal, gain, slope, print) as f32)
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
