def set_seed(seed: int) -> None:
    """Set the random seed for reproducibility."""
    import random
    random.seed(seed)


async def fetch_data(url: str) -> str:
    """Async helper."""
    return url
