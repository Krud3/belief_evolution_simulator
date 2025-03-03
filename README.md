# Spiral of Silence: Opinion Dynamics Simulator

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A scalable, high-performance simulator for studying opinion dynamics in social networks with the Spiral of Silence effect. This implementation is based on our paper "The Spiral of Silence in Multi-Agent DeGroot models" where we examine how silence behaviors impact consensus formation in social networks.

## Overview

This simulator implements two novel extensions to the classic DeGroot opinion dynamics framework:

- **Silence Opinion Memoryless (SOM-)**: Agents update their opinions by considering only non-silent neighbors' opinions. Silent agents are excluded from the opinion update process.
- **Silence Opinion Memory-based (SOM+)**: Agents update their opinions considering all neighbors, but for silent neighbors, only their most recent expressed opinion is used.
- **Confidence Silence Opinion Memoryless (CSOM-)**: Agents update their opinions by considering only non-silent neighbors' opinions. Silent agents are excluded from the opinion update process.
- **Confidence Silence Opinion Memory-based (CSOM+)**: Agents update their opinions considering all neighbors, but for silent neighbors, only their most recent expressed opinion is used.


These models capture the "Spiral of Silence" theory from political science, which describes how individuals may withhold their opinions when they perceive themselves to be in the minority.

## Features

- Simulate opinion dynamics in networks of up to 2.1 million agents (2²¹)
- Generate networks with small-world properties and power-law degree distributions
- Parallel computation support via Scala and Akka Actors
- Configurable parameters:
  - Tolerance radius (τ)
  - Majority threshold (M)
  - Network density
  - Initial opinion distributions
- Comprehensive results logging for analysis

## Prerequisites

- Java JDK 11 or higher
- Scala 3 or higher
- SBT (Scala Build Tool) 1.5.x or higher
- PostgreSQL (for storing simulation results)

## Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/YOUR_USERNAME/spiral-of-silence.git
   cd spiral-of-silence
   ```

2. Configure the database connection in `src/main/resources/application.conf`:
   ```hocon
   database {
     url = "jdbc:postgresql://localhost:5432/your_database"
     user = "your_username"
     password = "your_password"
   }
   ```

3. Build the project:
   ```bash
   sbt compile
   ```

## Running Simulations

### Basic Usage

Run a basic simulation with default parameters:

```bash
sbt "runMain com.spiralofsilence.Main"
```

### Custom Parameters

For custom simulation parameters:

```bash
sbt "runMain com.spiralofsilence.BatchRunner "
```

### Batch Simulations

For multiple simulation runs with parameter sweeps:

```bash
sbt "runMain com.spiralofsilence.BatchRunner "
```

## Code Structure


## Results

Simulation results are stored in PostgreSQL

## Citation

If you use this simulator in your research, please cite our paper:

```bibtex
@article{aranda2025spiral,
  title={The Spiral of Silence in Multi-Agent DeGroot models},
  author={Aranda, Jesús and Díaz, Juan Francisco and Gaona, David and Valencia, Frank},
  journal={[JOURNAL]},
  year={2025},
  publisher={[PUBLISHER]}
}
```

## Future Plans

- Docker Compose integration for easier setup and deployment
- Web-based visualization of simulation results
- Additional network topologies

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Thanks to all contributors and reviewers of our research
- This work was supported by 
