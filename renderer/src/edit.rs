use bytemuck::{Pod, Zeroable};
use grainlegit::Settings;
use serde::Deserialize;

#[derive(Clone, Copy, Debug, Deserialize, Default, PartialEq)]
#[serde(default)]
pub struct Edit {
    pub film: Settings,
    pub adjust: Adjust,
    pub lens: Lens,
}

impl Edit {
    pub fn padding(&self, size: [u32; 2]) -> Result<u32, String> {
        let edge = size[0].max(size[1]);
        let film = crate::emulsion::padding(edge, &self.film)?;
        let lens = if self.lens.effects().softness > 0.0 {
            softness_radius(size).ceil() as u32
        } else {
            0
        };
        Ok(film + lens + u32::from(self.adjust.tones[3] > 0.0))
    }
}

pub fn softness_radius([width, height]: [u32; 2]) -> f32 {
    0.0022 * (width as f32).hypot(height as f32)
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq)]
#[serde(default)]
pub struct Lens {
    pub aperture: f32,
    pub vignette: f32,
    pub glow: f32,
    pub haze: f32,
    pub softness: f32,
    pub warmth: f32,
    pub saturation: f32,
    pub ghosts: f32,
}

impl Default for Lens {
    fn default() -> Self {
        Self {
            aperture: 5.6,
            vignette: 0.0,
            glow: 0.0,
            haze: 0.0,
            softness: 0.0,
            warmth: 0.0,
            saturation: 0.0,
            ghosts: 0.0,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Default, PartialEq, Pod, Zeroable)]
pub struct LensEffects {
    pub vignette: f32,
    pub glow: f32,
    pub haze: f32,
    pub softness: f32,
    pub warmth: f32,
    pub saturation: f32,
    pub ghosts: f32,
    unused: f32,
}

impl LensEffects {
    pub fn needs_light(self) -> bool {
        self.glow > 0.0 || self.ghosts > 0.0
    }
}

impl Lens {
    pub fn effects(self) -> LensEffects {
        let stops = (2.0 * (self.aperture.clamp(1.4, 5.6) / 1.4).log2()).clamp(0.0, 4.0);
        let wide_open = (1.0 - stops / 2.0).max(0.0);
        let residual = 1.0 - stops / 4.0;
        LensEffects {
            vignette: self.vignette.clamp(-4.0, 0.0) * residual,
            glow: self.glow.clamp(0.0, 1.0) * wide_open.powi(2),
            haze: self.haze.clamp(0.0, 0.12) * residual.powi(2),
            softness: self.softness.clamp(0.0, 1.0) * wide_open,
            warmth: self.warmth.clamp(-1.0, 1.0) * residual,
            saturation: self.saturation.clamp(-1.0, 1.0) * residual,
            ghosts: self.ghosts.clamp(0.0, 1.0) * wide_open,
            unused: 0.0,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Pod, Zeroable)]
#[serde(default)]
pub struct Adjust {
    pub light: [f32; 4],       // exposure, brightness, highlights, shadows
    pub tones: [f32; 4],       // contrast, whites, blacks, sharpening
    pub colour: [f32; 4],      // temperature, tint, saturation, vibrance
    pub balance: [f32; 4],     // RGB white-balance gains and padding
    pub curves: [[f32; 4]; 5], // master and RGB at five fixed abscissae
}

impl Default for Adjust {
    fn default() -> Self {
        Self {
            light: [0.0; 4],
            tones: [0.0; 4],
            colour: [0.0; 4],
            balance: [1.0; 4],
            curves: std::array::from_fn(|i| [i as f32 * 0.25; 4]),
        }
    }
}
