import { Canvas } from './canvas/Canvas';
import { useBootstrapSamples } from './canvas/bootstrap';

export default function App() {
  useBootstrapSamples();
  return <Canvas />;
}
