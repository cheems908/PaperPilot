import torch
import torch.nn as nn


class PatchTST(nn.Module):
    """PatchTST: a patch-based time series forecasting model."""

    def __init__(self, num_channels: int = 7, patch_len: int = 16):
        self.num_channels = num_channels
        self.patch_len = patch_len

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """Forward pass of PatchTST."""
        return x


class PatchTSTHead(nn.Module):
    def __init__(self, out_dim: int = 1):
        self.out_dim = out_dim

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return x


def train_model(epochs: int = 100) -> None:
    """Train the PatchTST model."""
    pass
