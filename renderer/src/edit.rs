use bytemuck::{Pod, Zeroable};
use grainlegit::Settings;
use serde::Deserialize;

#[derive(Clone, Copy, Debug, Deserialize, Default, PartialEq)]
#[serde(default)]
pub struct Edit {
    pub film: Settings,
    pub adjust: Adjust,
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
